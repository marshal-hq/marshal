package dev.marshalhq.resolvers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marshalhq.core.Coordinates;

/**
 * Resolves a Maven project's dependencies from its POM.
 *
 * <p>Resolution is <b>transitive</b>: the POM's declared dependencies seed an Aether
 * {@code collectDependencies} walk that expands the full graph and applies conflict
 * resolution (nearest-wins → one version per {@code group:artifact}). The result is the
 * flattened, de-duplicated set of external modules — the same shape the Gradle path emits.
 *
 * <p>Honesty guarantees (S06): a POM that cannot be parsed at all surfaces as a
 * {@link ResolutionException} ("could not analyze"), never a clean empty result. Every
 * scope-included declared dependency is ALWAYS in the result — with its declared version,
 * or the {@code UNRESOLVED} sentinel when the version cannot be determined (unresolved
 * {@code ${property}} or a BOM-managed dep with no version) — regardless of whether the
 * transitive walk succeeds. A failed walk (offline, unreachable repo) degrades to a
 * direct-deps-only result instead of aborting, with every root reported via
 * {@link #unexpandedSubtrees()}. Missing/broken transitive descriptors are tolerated
 * (lenient descriptor policy) so one unreachable transitive never aborts the whole scan —
 * the affected node is likewise reported via {@link #unexpandedSubtrees()}: its own
 * dependencies were never walked, so that subtree is unscanned and must surface to the
 * user, never drop silently.
 */
public class PomDependencyResolver implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(PomDependencyResolver.class);
    private static final Set<DependencyScope> DEFAULT_SCOPES = EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME);
    private static final ModelBuilder MODEL_BUILDER = new DefaultModelBuilderFactory().newInstance();
    private static final String UNRESOLVED = "UNRESOLVED";

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repos;
    private final Set<DependencyScope> includedScopes;
    private volatile List<Coordinates> unexpandedSubtrees = List.of();

    public PomDependencyResolver() {
        this(DEFAULT_SCOPES);
    }

    public PomDependencyResolver(Collection<DependencyScope> includedScopes) {
        this.includedScopes = includedScopes.isEmpty()
                ? EnumSet.noneOf(DependencyScope.class)
                : EnumSet.copyOf(includedScopes);
        this.system = new RepositorySystemSupplier().get();
        this.session = newSession(system);
        this.repos = List.of(new RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build());
    }

    @Override
    public List<Coordinates> resolve(Path pomPath) {
        Model model = buildEffectiveModel(pomPath);

        // Declared deps are ground truth: every scope-included direct lands in the result
        // up front — so a failed transitive walk can never drop a declared dependency (S06).
        // Concrete-version directs also seed the walk; nearest-wins conflict resolution means
        // a direct dep's declared version IS the winning version, so seeding it first is safe.
        List<org.eclipse.aether.graph.Dependency> roots = new ArrayList<>();
        Map<String, Coordinates> byGa = new LinkedHashMap<>();
        List<Coordinates> unresolvedDirects = new ArrayList<>();
        for (Dependency dep : declaredDependencies(model)) {
            if (!isIncludedScope(dep.getScope())) {
                continue;
            }
            String version = effectiveVersion(dep);
            if (UNRESOLVED.equals(version)) {
                // Held back until after the walk: a concrete version surfaced transitively
                // (via another declared dep) beats the sentinel for the same group:artifact.
                unresolvedDirects.add(new Coordinates(dep.getGroupId(), dep.getArtifactId(), UNRESOLVED));
                continue;
            }
            byGa.putIfAbsent(toGa(dep), new Coordinates(dep.getGroupId(), dep.getArtifactId(), version));
            roots.add(toAetherDependency(dep, version));
        }

        // Expand transitively. Directs are already present, so first-seen-wins putIfAbsent
        // only ever adds new group:artifact entries.
        Set<Coordinates> descriptorFailures = ConcurrentHashMap.newKeySet();
        for (Coordinates c : collectTransitive(roots, repositoriesFor(model), pomPath, descriptorFailures)) {
            byGa.putIfAbsent(c.toGa(), c);
        }

        // Sentinels last: only a GA with no concrete version anywhere stays UNRESOLVED.
        for (Coordinates c : unresolvedDirects) {
            byGa.putIfAbsent(c.toGa(), c);
        }

        // A tolerated descriptor failure means that node's own dependencies were never
        // walked (S06: an unscanned subtree must surface, never drop silently). Keep only
        // failures whose GAV survived conflict resolution — an evicted version's subtree
        // was never part of the resolved tree anyway.
        List<Coordinates> unexpanded = new ArrayList<>();
        for (Coordinates failed : descriptorFailures) {
            Coordinates winner = byGa.get(failed.toGa());
            if (winner != null && winner.version().equals(failed.version())) {
                log.warn("Descriptor for {} could not be read — its transitive subtree was NOT scanned", failed.toGav());
                unexpanded.add(failed);
            }
        }
        this.unexpandedSubtrees = List.copyOf(unexpanded);

        return List.copyOf(byGa.values());
    }

    @Override
    public List<Coordinates> unexpandedSubtrees() {
        return unexpandedSubtrees;
    }

    /**
     * Package-private: the POM's declared (direct) dependencies only — the effective-model
     * parse layer, with scope filtering and the UNRESOLVED sentinel, but no transitive walk.
     * Exposed so the offline unit tests can exercise parsing/scope/UNRESOLVED without a
     * repository. Production callers use {@link #resolve(Path)} (transitive).
     */
    List<Coordinates> directDependencies(Path pomPath) {
        Model model = buildEffectiveModel(pomPath);
        List<Coordinates> result = new ArrayList<>();
        for (Dependency dep : declaredDependencies(model)) {
            if (!isIncludedScope(dep.getScope())) {
                continue;
            }
            result.add(new Coordinates(dep.getGroupId(), dep.getArtifactId(), effectiveVersion(dep)));
        }
        return result;
    }

    private Model buildEffectiveModel(Path pomPath) {
        try {
            DefaultModelBuildingRequest req = new DefaultModelBuildingRequest();
            req.setPomFile(pomPath.toFile());
            req.setModelResolver(new AetherModelResolver(system, session, new ArrayList<>(repos)));
            req.setProcessPlugins(false);
            req.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            // System properties feed interpolation of ${...} in model strings (e.g. repository URLs).
            req.setSystemProperties(System.getProperties());
            return MODEL_BUILDER.build(req).getEffectiveModel();
        } catch (ModelBuildingException e) {
            // Validation errors (e.g. missing version) produce a partial model that is still usable.
            if (e.getResult() != null && e.getResult().getEffectiveModel() != null) {
                log.debug("Non-fatal model building problems for {}: {}", pomPath, e.getMessage());
                return e.getResult().getEffectiveModel();
            }
            // Total failure — no usable model. NOT the same as a pom with zero deps (S06).
            throw new ResolutionException(
                    "could not resolve dependencies for " + pomPath + ": " + e.getMessage());
        } catch (ResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolutionException(
                    "could not resolve dependencies for " + pomPath + ": " + e.getMessage());
        }
    }

    /** Declared dependencies: the {@code <dependencies>} block, plus (for BOM/parent POMs
     * with {@code packaging=pom}) the {@code dependencyManagement} block, skipping BOM imports. */
    private static List<Dependency> declaredDependencies(Model model) {
        List<Dependency> deps = new ArrayList<>(model.getDependencies());
        if ("pom".equals(model.getPackaging()) && model.getDependencyManagement() != null) {
            for (Dependency dep : model.getDependencyManagement().getDependencies()) {
                if ("import".equals(dep.getScope()) && "pom".equals(dep.getType())) {
                    continue;
                }
                deps.add(dep);
            }
        }
        return deps;
    }

    /**
     * Walks the transitive graph seeded by {@code roots}, returning the flattened external
     * modules (post conflict resolution, de-duplicated by group:artifact, first-seen order).
     * Descriptors the lenient policy tolerated (missing/invalid — subtree not walked) are
     * recorded into {@code descriptorFailures}; the reader fires the descriptor-missing/
     * -invalid repository events before the policy decides, so a genuine leaf (descriptor
     * read fine, zero deps) never lands in the set.
     */
    private List<Coordinates> collectTransitive(List<org.eclipse.aether.graph.Dependency> roots,
            List<RemoteRepository> repositories, Path pomPath, Set<Coordinates> descriptorFailures) {
        if (roots.isEmpty()) {
            return List.of();
        }
        CollectRequest request = new CollectRequest();
        request.setDependencies(roots);
        request.setRepositories(repositories);
        // The collector may read descriptors on multiple threads; the listener session is
        // per-walk so concurrent resolve() calls cannot cross-contaminate.
        DefaultRepositorySystemSession walkSession = new DefaultRepositorySystemSession(session);
        walkSession.setRepositoryListener(new AbstractRepositoryListener() {
            @Override
            public void artifactDescriptorMissing(RepositoryEvent event) {
                record(event);
            }

            @Override
            public void artifactDescriptorInvalid(RepositoryEvent event) {
                record(event);
            }

            private void record(RepositoryEvent event) {
                Artifact a = event.getArtifact();
                if (a != null) {
                    log.debug("Descriptor {} for {}: {}", event.getType(), a, String.valueOf(event.getException()));
                    descriptorFailures.add(new Coordinates(a.getGroupId(), a.getArtifactId(), a.getBaseVersion()));
                }
            }
        });
        try {
            CollectResult result = system.collectDependencies(walkSession, request);
            return flatten(result.getRoot());
        } catch (DependencyCollectionException e) {
            // The declared directs are already seeded into the result by resolve(), so a
            // failed walk (offline, unreachable repo, connector error) degrades to whatever
            // partial graph exists instead of aborting the scan or dropping directs (S06).
            // We cannot tell which subtrees the collector finished before failing, so every
            // root is conservatively reported as unexpanded — over-reporting the gap is the
            // honest direction.
            log.warn("Transitive resolution incomplete for {}: {}", pomPath, e.getMessage());
            for (org.eclipse.aether.graph.Dependency root : roots) {
                Artifact a = root.getArtifact();
                descriptorFailures.add(new Coordinates(a.getGroupId(), a.getArtifactId(), a.getBaseVersion()));
            }
            CollectResult partial = e.getResult();
            if (partial != null && partial.getRoot() != null) {
                return flatten(partial.getRoot());
            }
            return List.of();
        }
    }

    /** Builds the Aether root dependency for a declared direct, carrying type, classifier,
     * scope AND the declared {@code <exclusions>} — dropping them would let excluded
     * transitives leak back into the graph. */
    private static org.eclipse.aether.graph.Dependency toAetherDependency(Dependency dep, String version) {
        String type = dep.getType() == null ? "jar" : dep.getType();
        String classifier = dep.getClassifier() == null ? "" : dep.getClassifier();
        String scope = dep.getScope() == null ? "compile" : dep.getScope();
        List<Exclusion> exclusions = new ArrayList<>();
        for (org.apache.maven.model.Exclusion e : dep.getExclusions()) {
            exclusions.add(new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"));
        }
        return new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact(dep.getGroupId(), dep.getArtifactId(), classifier, type, version),
                scope, false, exclusions);
    }

    private List<Coordinates> flatten(DependencyNode root) {
        Map<String, Coordinates> byGa = new LinkedHashMap<>();
        // Identity-keyed visited set: a partial graph (failed walk) has NOT been through
        // ConflictResolver, so it may contain cycles/shared subtrees — walk each node once.
        Set<DependencyNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectNodes(root, byGa, visited);
        return List.copyOf(byGa.values());
    }

    private void collectNodes(DependencyNode node, Map<String, Coordinates> byGa, Set<DependencyNode> visited) {
        if (!visited.add(node)) {
            return;
        }
        for (DependencyNode child : node.getChildren()) {
            Artifact a = child.getArtifact();
            // Transitive nodes honor the same scope filter as directs: Aether's default
            // selector only prunes test/provided, so e.g. a runtime transitive would
            // otherwise leak into a COMPILE-only-configured resolver.
            if (a != null && isIncludedScope(nodeScope(child))) {
                Coordinates c = new Coordinates(a.getGroupId(), a.getArtifactId(), a.getBaseVersion());
                byGa.putIfAbsent(c.toGa(), c);
            }
            collectNodes(child, byGa, visited);
        }
    }

    /** The node's mediated scope; null (→ compile default) when absent or empty. */
    private static String nodeScope(DependencyNode node) {
        if (node.getDependency() == null) {
            return null;
        }
        String scope = node.getDependency().getScope();
        return scope == null || scope.isEmpty() ? null : scope;
    }

    /** POM-declared repositories first (so local/mirror repos win), then Maven Central.
     * Carries the POM's releases/snapshots policies so e.g. a snapshots-disabled repo is
     * not queried for snapshots. (settings.xml auth/mirrors are still unsupported.) */
    private List<RemoteRepository> repositoriesFor(Model model) {
        List<RemoteRepository> out = new ArrayList<>();
        for (Repository r : model.getRepositories()) {
            out.add(new RemoteRepository.Builder(r.getId(), "default", r.getUrl())
                    .setReleasePolicy(toPolicy(r.getReleases()))
                    .setSnapshotPolicy(toPolicy(r.getSnapshots()))
                    .build());
        }
        out.addAll(repos);
        return out;
    }

    /** Maps a POM repository policy; an absent block means Maven's default (enabled). */
    private static RepositoryPolicy toPolicy(org.apache.maven.model.RepositoryPolicy p) {
        if (p == null) {
            return new RepositoryPolicy(true,
                    RepositoryPolicy.UPDATE_POLICY_DAILY, RepositoryPolicy.CHECKSUM_POLICY_WARN);
        }
        String update = p.getUpdatePolicy() == null || p.getUpdatePolicy().isEmpty()
                ? RepositoryPolicy.UPDATE_POLICY_DAILY : p.getUpdatePolicy();
        String checksum = p.getChecksumPolicy() == null || p.getChecksumPolicy().isEmpty()
                ? RepositoryPolicy.CHECKSUM_POLICY_WARN : p.getChecksumPolicy();
        return new RepositoryPolicy(p.isEnabled(), update, checksum);
    }

    private boolean isIncludedScope(String scope) {
        // null scope means compile (Maven default)
        DependencyScope effective = scope == null
                ? DependencyScope.COMPILE
                : DependencyScope.from(scope).orElse(null);
        return effective != null && includedScopes.contains(effective);
    }

    private static String toGa(Dependency dep) {
        return dep.getGroupId() + ":" + dep.getArtifactId();
    }

    private static String effectiveVersion(Dependency dep) {
        String version = dep.getVersion();
        // Any surviving '$' means interpolation left an unresolved ${property} behind —
        // including mid-string ones like "1.${rev}", not just a leading "${...}".
        if (version == null || version.contains("$")) {
            log.warn("Unresolvable version for {}:{} — included as UNRESOLVED", dep.getGroupId(), dep.getArtifactId());
            return UNRESOLVED;
        }
        return version;
    }

    private static RepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        // Descriptor reads during the graph walk build each dependency's effective model;
        // JDK-activated profiles (e.g. commons-parent's java9+) need java.version et al.
        // from the session, or every such POM fails as an invalid descriptor and its
        // subtree is skipped. Same reason the root model request sets system properties.
        session.setSystemProperties(System.getProperties());
        String repoPath = System.getProperty("marshal.localRepo",
                System.getProperty("user.home") + "/.m2/repository");
        LocalRepository localRepo = new LocalRepository(repoPath);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
        // Tolerate missing/invalid transitive POMs: one unreachable descriptor must not abort
        // the whole graph walk (the node still appears; it just isn't expanded further).
        session.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(true, true));
        return session;
    }

    @SuppressWarnings("deprecation")
    private static final class AetherModelResolver implements ModelResolver {

        private final RepositorySystem system;
        private final RepositorySystemSession session;
        private final List<RemoteRepository> repos;

        AetherModelResolver(RepositorySystem system, RepositorySystemSession session, List<RemoteRepository> repos) {
            this.system = system;
            this.session = session;
            this.repos = new ArrayList<>(repos);
        }

        @Override
        public ModelSource resolveModel(String groupId, String artifactId, String version)
                throws UnresolvableModelException {
            try {
                DefaultArtifact pom = new DefaultArtifact(groupId, artifactId, "", "pom", version);
                ArtifactRequest request = new ArtifactRequest(pom, repos, null);
                ArtifactResult result = system.resolveArtifact(session, request);
                return new FileModelSource(result.getArtifact().getFile());
            } catch (ArtifactResolutionException e) {
                throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
            }
        }

        @Override
        public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
            try {
                return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
            } catch (UnresolvableModelException e) {
                log.debug("Parent {}:{}:{} not resolvable, continuing without it",
                        parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                return stubPomSource(parent.getGroupId(), parent.getArtifactId());
            }
        }

        private static ModelSource stubPomSource(String groupId, String artifactId) {
            String xml = "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                    + "<groupId>" + groupId + "</groupId>"
                    + "<artifactId>" + artifactId + "</artifactId>"
                    + "<version>STUB</version></project>";
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            return new ModelSource() {
                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
                @Override
                public String getLocation() {
                    return groupId + ":" + artifactId + ":STUB";
                }
            };
        }

        @Override
        public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
            return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        }

        @Override
        public void addRepository(Repository repository) {
            addRepository(repository, false);
        }

        @Override
        public void addRepository(Repository repository, boolean replace) {
            boolean exists = repos.stream().anyMatch(r -> r.getId().equals(repository.getId()));
            if (!exists || replace) {
                repos.removeIf(r -> r.getId().equals(repository.getId()));
                repos.add(new RemoteRepository.Builder(repository.getId(), "default", repository.getUrl()).build());
            }
        }

        @Override
        public ModelResolver newCopy() {
            return new AetherModelResolver(system, session, new ArrayList<>(repos));
        }
    }
}
