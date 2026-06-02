package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.core.rules.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.registry.MetadataCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

/** Static helpers shared by ScanCommand and DiffCommand. */
class CliHelper {

    private static final Logger log = LoggerFactory.getLogger(CliHelper.class);

    private CliHelper() {}

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
        try {
            var cacheDir = Paths.get(System.getProperty("user.home"), ".marshal");
            cacheDir.toFile().mkdirs();
            MetadataCache cache = new MetadataCache(cacheDir.resolve("metadata.db"));
            return new MavenCentralClient(cache);
        } catch (Exception e) {
            log.warn("Could not initialise metadata cache, running without cache: {}", e.getMessage());
            return new MavenCentralClient();
        }
    }

    static Set<String> loadHighReputationGAs() {
        Set<String> gas = new HashSet<>();
        try (var in = CliHelper.class.getClassLoader()
                .getResourceAsStream("high-reputation-gavs.txt")) {
            if (in == null) return gas;
            new BufferedReader(new InputStreamReader(in)).lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .forEach(gas::add);
        } catch (Exception e) {
            log.warn("Could not load high-reputation GA list: {}", e.getMessage());
        }
        return gas;
    }

    static int computeExitCode(List<Finding> findings, Severity threshold,
                                FailOn failOn, PrintWriter warn) {
        Optional<Severity> worst = findings.stream()
            .filter(f -> !f.isUnresolved() && f.riskLevel() != null)
            .map(Finding::riskLevel)
            .max(Comparator.comparingInt(Severity::ordinal));

        if (worst.isEmpty() || worst.get().ordinal() < threshold.ordinal()) return 0;

        return switch (failOn) {
            case FAIL  -> 1;
            case WARN  -> {
                warn.println("[WARN] marshal: findings at or above threshold '" +
                    threshold.name().toLowerCase() + "' detected.");
                yield 0;
            }
            case NEVER -> 0;
        };
    }

    static boolean isUnresolved(Coordinates c) {
        return "UNRESOLVED".equals(c.version());
    }

    static void acquire(Semaphore sem) {
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void awaitAll(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            log.warn("One or more parallel fetches failed: {}", e.getCause().getMessage());
        }
    }

    static Severity parseLevel(String s, Severity fallback) {
        try { return Severity.valueOf(s.toUpperCase()); }
        catch (Exception e) { return fallback; }
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
            highRepGAs.contains(coords.toGa())
        );
        RuleEngine.EvaluationDetail detail = engine.evaluateWithDetails(ctx);
        boolean unknownMeta = current.signatureStatus() == SignatureStatus.UNKNOWN
            || current.dependencyCount() == -1;
        return new Finding(coords, fromVersion, coords.version(),
            detail.score().score(), detail.score().level(),
            detail.firedRules(), false, unknownMeta);
    }
}
