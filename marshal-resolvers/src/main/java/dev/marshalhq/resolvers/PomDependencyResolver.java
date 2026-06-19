package dev.marshalhq.resolvers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marshalhq.core.Coordinates;

public class PomDependencyResolver implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(PomDependencyResolver.class);
    private static final Set<DependencyScope> DEFAULT_SCOPES = EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME);
    private static final ModelBuilder MODEL_BUILDER = new DefaultModelBuilderFactory().newInstance();

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repos;
    private final Set<DependencyScope> includedScopes;

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
        Model model;
        try {
            DefaultModelBuildingRequest req = new DefaultModelBuildingRequest();
            req.setPomFile(pomPath.toFile());
            req.setModelResolver(new AetherModelResolver(system, session, new ArrayList<>(repos)));
            req.setProcessPlugins(false);
            req.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            model = MODEL_BUILDER.build(req).getEffectiveModel();
        } catch (ModelBuildingException e) {
            // Validation errors (e.g. missing version) produce a partial model that is still usable.
            if (e.getResult() != null && e.getResult().getEffectiveModel() != null) {
                model = e.getResult().getEffectiveModel();
                log.debug("Non-fatal model building problems for {}: {}", pomPath, e.getMessage());
            }
            else {
                // Total failure — no usable model. NOT the same as a pom with zero deps (S06);
                // partial models with unresolvable versions are handled below as UNRESOLVED.
                throw new ResolutionException(
                        "could not resolve dependencies for " + pomPath + ": " + e.getMessage());
            }
        } catch (ResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new ResolutionException(
                    "could not resolve dependencies for " + pomPath + ": " + e.getMessage());
        }

        List<Coordinates> result = new ArrayList<>();
        for (Dependency dep : model.getDependencies()) {
            if (!isIncludedScope(dep.getScope())) {
                continue;
            }
            result.add(new Coordinates(dep.getGroupId(), dep.getArtifactId(), effectiveVersion(dep)));
        }

        // For BOM/parent POMs (packaging=pom), the dependencyManagement section IS the
        // dependency declaration — scan it too, skipping BOM imports (scope=import, type=pom).
        if ("pom".equals(model.getPackaging()) && model.getDependencyManagement() != null) {
            for (Dependency dep : model.getDependencyManagement().getDependencies()) {
                if ("import".equals(dep.getScope()) && "pom".equals(dep.getType())) {
                    continue;
                }
                if (!isIncludedScope(dep.getScope())) {
                    continue;
                }
                result.add(new Coordinates(dep.getGroupId(), dep.getArtifactId(), effectiveVersion(dep)));
            }
        }

        return result;
    }

    private boolean isIncludedScope(String scope) {
        // null scope means compile (Maven default)
        DependencyScope effective = scope == null
                ? DependencyScope.COMPILE
                : DependencyScope.from(scope).orElse(null);
        return effective != null && includedScopes.contains(effective);
    }

    private static String effectiveVersion(Dependency dep) {
        String version = dep.getVersion();
        if (version == null || version.startsWith("$")) {
            log.warn("Unresolvable version for {}:{} — included as UNRESOLVED", dep.getGroupId(), dep.getArtifactId());
            return "UNRESOLVED";
        }
        return version;
    }

    private static RepositorySystemSession newSession(RepositorySystem system) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(System.getProperty("user.home") + "/.m2/repository");
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));
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
