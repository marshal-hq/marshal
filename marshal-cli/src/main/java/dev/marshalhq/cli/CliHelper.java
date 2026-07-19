package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.core.config.RulesConfig;
import dev.marshalhq.core.config.RulesConfig.RuleOverride;
import dev.marshalhq.core.rules.RuleCatalog;
import dev.marshalhq.core.rules.WeightOverrideRule;
import dev.marshalhq.core.whitelist.Whitelist;
import dev.marshalhq.core.whitelist.WhitelistLoader;
import dev.marshalhq.core.whitelist.Whitelists;
import dev.marshalhq.registry.MarshalWhitelistProvider;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.registry.MetadataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

/**
 * Static helpers shared by ScanCommand and DiffCommand.
 */
class CliHelper {

    private static final Logger log = LoggerFactory.getLogger(CliHelper.class);

    private CliHelper() {
    }

    /** Default engine with every shipped rule at its built-in weight. */
    static RuleEngine buildEngine() {
        return buildEngine(new RulesConfig());
    }

    /**
     * Builds the engine from config: rules listed in {@code rules.disabled} are dropped,
     * and a {@code rules.overrides} weight replaces a rule's built-in score. IDs are
     * already validated against {@link RuleCatalog} at config load time, so anything left
     * here is a known rule.
     */
    static RuleEngine buildEngine(RulesConfig rules) {
        Set<String> disabled = Set.copyOf(rules.getDisabled());
        Map<String, RuleOverride> overrides = rules.getOverrides();
        List<Rule> active = new ArrayList<>();
        for (Rule rule : RuleCatalog.defaults()) {
            if (disabled.contains(rule.id())) {
                continue;
            }
            RuleOverride override = overrides.get(rule.id());
            active.add(override != null
                    ? new WeightOverrideRule(rule, override.getWeight())
                    : rule);
        }
        return new RuleEngine(active);
    }

    static MavenCentralClient buildProductionClient() {
        return buildProductionClient(null);
    }

    static MavenCentralClient buildProductionClient(Path cachePath) {
        try {
            Path resolvedPath = cachePath != null
                ? cachePath
                : Paths.get(System.getProperty("user.home"), ".marshal", "metadata.db");
            resolvedPath.toAbsolutePath().getParent().toFile().mkdirs();
            MetadataCache cache = new MetadataCache(resolvedPath);
            return new MavenCentralClient(cache);
        }
        catch (Exception e) {
            log.warn("Could not initialise metadata cache, running without cache: {}", e.getMessage());
            return new MavenCentralClient();
        }
    }

    static Set<String> loadHighReputationGAs() {
        Set<String> gas = new HashSet<>();
        try (var in = CliHelper.class.getClassLoader()
                .getResourceAsStream("high-reputation-gavs.txt")) {
            if (in == null) {
                return gas;
            }
            new BufferedReader(new InputStreamReader(in)).lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .forEach(gas::add);
        }
        catch (Exception e) {
            log.warn("Could not load high-reputation GA list: {}", e.getMessage());
        }
        return gas;
    }

    // Match a GA against the high-reputation list. Supports exact entries
    // (org.apache.commons:commons-io) and "groupId:*" wildcards so an entire
    // vendor family (e.g. the Apache Commons artifacts) can be tagged at once.
    static boolean isHighReputation(Set<String> patterns, String ga) {
        return patterns.stream().anyMatch(pattern -> {
            if (pattern.endsWith(":*")) {
                return ga.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return ga.equals(pattern);
        });
    }

    // ── whitelist suppression ──────────────────────────────────────────────────────

    /**
     * Builds the active {@link Whitelists} for a scan: the user file ({@code
     * marshal-whitelist.yml} resolved next to the scanned project) plus the
     * Marshal-maintained list. {@code frozen} forces the embedded baseline only (no
     * remote refresh) for reproducible CI; otherwise the freshest verified source wins.
     */
    static Whitelists loadWhitelists(Path scanTarget, boolean frozen, Path cachePath) {
        Whitelist user = WhitelistLoader.loadUser(userWhitelistPath(scanTarget));
        Whitelist marshal = MarshalWhitelistProvider.active(frozen, cachePath);
        return new Whitelists(marshal, user);
    }

    /** The user whitelist lives at the repo root, next to the build file. */
    static Path userWhitelistPath(Path scanTarget) {
        Path dir;
        if (scanTarget == null) {
            dir = Paths.get(".");
        }
        else if (Files.isDirectory(scanTarget)) {
            dir = scanTarget;
        }
        else {
            dir = scanTarget.getParent();
        }
        if (dir == null) {
            dir = Paths.get(".");
        }
        return dir.resolve(WhitelistLoader.USER_FILENAME);
    }

    /**
     * Marks a finding suppressed when its exact GAV is whitelisted (and unexpired) and
     * the engine actually raised it. GREEN and unresolved findings are never suppressed —
     * there is nothing to suppress, and moving them would corrupt the safe/unresolved
     * counts. Order is preserved.
     */
    static List<Finding> applySuppression(List<Finding> findings, Whitelists whitelists, LocalDate asOf) {
        List<Finding> out = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            if (f.isUnresolved() || f.riskLevel() == null || f.riskLevel() == Severity.GREEN) {
                out.add(f);
                continue;
            }
            Optional<SuppressionInfo> info = whitelists.decide(f.coordinates().toGav(), asOf);
            out.add(info.map(f::withSuppression).orElse(f));
        }
        return out;
    }

    static int computeExitCode(ScanReport report, Severity threshold, FailOn failOn) {
        Optional<Severity> worst = report.worstSeverity();

        if (worst.isEmpty() || worst.get().ordinal() < threshold.ordinal()) {
            return 0;
        }

        return switch (failOn) {
            case FAIL -> 1;
            case WARN -> {
                // Write to stderr so machine-readable stdout (--output json/md) is not corrupted
                System.err.println("[WARN] marshal: findings at or above threshold '" +
                        threshold.name().toLowerCase() + "' detected.");
                yield 0;
            }
            case NEVER -> 0;
        };
    }

    static boolean isUnresolved(Coordinates c) {
        // UNRESOLVED: version was null/unresolvable in the POM.
        // STUB: version inherited from a private parent POM that couldn't be fetched
        //   from Maven Central (see PomDependencyResolver.stubPomSource). Both sentinels
        //   mean the version is unknown — treat them identically.
        String v = c.version();
        return "UNRESOLVED".equals(v) || "STUB".equals(v);
    }

    static void acquire(Semaphore sem) {
        try {
            sem.acquire();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void awaitAll(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        catch (CompletionException e) {
            log.warn("One or more parallel fetches failed: {}", e.getCause().getMessage());
        }
    }

    static Severity parseLevel(String s, Severity fallback) {
        try {
            return Severity.valueOf(s.toUpperCase());
        }
        catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Attaches the introduced-by paths for the finding's {@code group:artifact}, if the
     * resolver recorded any. Display metadata only — attached after scoring, and absent
     * entries leave the finding (and every serialized output) unchanged.
     */
    static Finding attachPaths(Finding finding, Map<String, List<List<DependencyPathNode>>> pathsByGa) {
        List<List<DependencyPathNode>> paths = pathsByGa.get(finding.coordinates().toGa());
        return paths == null || paths.isEmpty() ? finding : finding.withIntroducedBy(paths);
    }

    static VersionMetadata stub(Coordinates coords) {
        return new VersionMetadata(coords, null, null, SignatureStatus.UNKNOWN,
                List.of(), -1, null, Instant.EPOCH, false);
    }

    static Finding toFinding(Coordinates coords, String fromVersion,
            VersionMetadata current, VersionMetadata previous,
            RuleEngine engine, Set<String> highRepGAs) {
        List<VersionMetadata> history = previous != null ? List.of(previous) : List.of();
        PackageContext ctx = new PackageContext(
                coords, current, previous, history,
                new TarballAnalysis(false, false, ""),
                isHighReputation(highRepGAs, coords.toGa())
        );
        RuleEngine.EvaluationDetail detail = engine.evaluateWithDetails(ctx);
        boolean unknownMeta = current.signatureStatus() == SignatureStatus.UNKNOWN || current.dependencyCount() == -1;
        return new Finding(coords, fromVersion, coords.version(),
                detail.score().score(), detail.score().level(),
                detail.firedRules(), false, unknownMeta);
    }
}
