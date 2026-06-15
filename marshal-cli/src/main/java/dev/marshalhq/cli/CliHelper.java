package dev.marshalhq.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.PackageContext;
import dev.marshalhq.core.RuleEngine;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.SignatureStatus;
import dev.marshalhq.core.TarballAnalysis;
import dev.marshalhq.core.VersionMetadata;
import dev.marshalhq.core.rules.DependencyExplosionRule;
import dev.marshalhq.core.rules.MajorVersionJumpRule;
import dev.marshalhq.core.rules.MissingSignatureRule;
import dev.marshalhq.core.rules.NewMaintainerRule;
import dev.marshalhq.core.rules.RepoUrlChangedRule;
import dev.marshalhq.core.rules.SignatureDroppedRule;
import dev.marshalhq.core.rules.YankedVersionRule;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.registry.MetadataCache;

/**
 * Static helpers shared by ScanCommand and DiffCommand.
 */
class CliHelper {

    private static final Logger log = LoggerFactory.getLogger(CliHelper.class);

    private CliHelper() {
    }

    static RuleEngine buildEngine() {
        return new RuleEngine(List.of(
                new MissingSignatureRule(),
                new SignatureDroppedRule(),
                new MajorVersionJumpRule(),
                new NewMaintainerRule(),
                new DependencyExplosionRule(),
                new RepoUrlChangedRule(),
                new YankedVersionRule()
        ));
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
