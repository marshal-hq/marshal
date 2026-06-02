package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.core.config.MarshalConfigLoader;
import dev.marshalhq.core.rules.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.registry.MetadataCache;
import dev.marshalhq.resolvers.PomDependencyResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Command(
    name = "scan",
    description = "Scan a POM file for risky dependency updates.",
    mixinStandardHelpOptions = true
)
public class ScanCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);

    @Option(names = "--pom", description = "Path to the pom.xml to scan", required = true)
    Path pomPath;

    @Option(names = "--output", description = "Output format: human, json, md (default: human)")
    OutputFormat outputFormat = OutputFormat.HUMAN;

    @Option(names = "--threshold", description = "Risk level that triggers failure: green, yellow, orange, red (default: red)")
    Severity threshold = Severity.RED;

    @Option(names = "--fail-on", description = "Exit code behavior: fail, warn, never (default: fail)")
    FailOn failOn = FailOn.FAIL;

    @Option(names = "--config", description = "Path to marshal.yml config file")
    Path configPath;

    // --- injected for testing; null = constructed from scratch in call() ---
    private final MavenCentralClient injectedClient;
    private final PomDependencyResolver injectedResolver;

    public ScanCommand() {
        this.injectedClient = null;
        this.injectedResolver = null;
    }

    /** Package-private: inject components for testing. */
    ScanCommand(MavenCentralClient client, PomDependencyResolver resolver) {
        this.injectedClient = client;
        this.injectedResolver = resolver;
    }

    @Override
    public Integer call() {
        MarshalConfig config = MarshalConfigLoader.load(configPath);
        PomDependencyResolver resolver = injectedResolver != null
            ? injectedResolver : new PomDependencyResolver();
        MavenCentralClient client = injectedClient != null
            ? injectedClient : buildProductionClient(config);

        RuleEngine engine = buildEngine();
        Set<String> highRepGAs = loadHighReputationGAs();

        List<Coordinates> allDeps = resolver.resolve(pomPath);
        if (allDeps.isEmpty()) {
            out().println("No dependencies found in " + pomPath);
            return 0;
        }

        // Partition: UNRESOLVED versions are surfaced directly, not evaluated.
        List<Coordinates> resolved   = allDeps.stream().filter(c -> !isUnresolved(c)).toList();
        List<Coordinates> unresolved = allDeps.stream().filter(ScanCommand::isUnresolved).toList();

        Semaphore semaphore = new Semaphore(24);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Step 1: fetch version histories for all resolved deps in parallel.
        ConcurrentHashMap<String, List<String>> histories = new ConcurrentHashMap<>();
        awaitAll(resolved.stream().map(coords ->
            CompletableFuture.runAsync(() -> {
                acquire(semaphore);
                try {
                    histories.put(coords.toGa(),
                        client.getVersionHistory(coords.groupId(), coords.artifactId()));
                } finally {
                    semaphore.release();
                }
            }, executor)
        ).toList());

        // Step 2: determine previous version for each dep.
        Map<String, Coordinates> previousCoords = new HashMap<>();
        for (Coordinates coords : resolved) {
            List<String> history = histories.getOrDefault(coords.toGa(), List.of());
            int idx = history.indexOf(coords.version());
            if (idx >= 0 && idx + 1 < history.size()) {
                previousCoords.put(coords.toGav(),
                    new Coordinates(coords.groupId(), coords.artifactId(), history.get(idx + 1)));
            }
        }

        // Step 3: fan-out metadata fetches for current + previous in parallel.
        ConcurrentHashMap<String, VersionMetadata> metaByGav = new ConcurrentHashMap<>();
        List<Coordinates> toFetch = new ArrayList<>(resolved);
        previousCoords.values().forEach(toFetch::add);

        awaitAll(toFetch.stream().map(coords ->
            CompletableFuture.runAsync(() -> {
                acquire(semaphore);
                try {
                    metaByGav.put(coords.toGav(), client.fetchMetadata(coords));
                } finally {
                    semaphore.release();
                }
            }, executor)
        ).toList());

        executor.shutdown();

        // Step 4: assemble PackageContext and evaluate each dep.
        List<Finding> findings = new ArrayList<>();

        for (Coordinates coords : resolved) {
            VersionMetadata current = metaByGav.get(coords.toGav());
            if (current == null) {
                // fetchMetadata never returns null, but be safe
                current = stub(coords);
            }

            Coordinates prevCoords = previousCoords.get(coords.toGav());
            VersionMetadata previous = prevCoords != null ? metaByGav.get(prevCoords.toGav()) : null;

            List<VersionMetadata> history = previous != null ? List.of(previous) : List.of();
            boolean highRep = highRepGAs.contains(coords.toGa());

            PackageContext ctx = new PackageContext(
                coords, current, previous, history,
                new TarballAnalysis(false, false, ""),
                highRep
            );

            RuleEngine.EvaluationDetail detail = engine.evaluateWithDetails(ctx);
            boolean unknownMeta = current.signatureStatus() == SignatureStatus.UNKNOWN
                || current.dependencyCount() == -1;

            findings.add(new Finding(
                coords,
                prevCoords != null ? prevCoords.version() : null,
                coords.version(),
                detail.score().score(),
                detail.score().level(),
                detail.firedRules(),
                false,
                unknownMeta
            ));
        }

        // Add unresolved entries — no evaluation, just surfaced.
        for (Coordinates coords : unresolved) {
            findings.add(Finding.unresolved(coords));
        }

        // Step 5: route to reporter.
        Instant scannedAt = Instant.now();
        PrintWriter writer = new PrintWriter(System.out, true);
        Reporter reporter = switch (outputFormat) {
            case HUMAN -> new TerminalReporter();
            case JSON  -> new JsonReporter(pomPath.toString(), scannedAt);
            case MD    -> new PlainTextReporter(); // Block 4 replaces this
        };
        reporter.report(findings, writer);
        writer.flush();

        // Step 6: compute exit code.
        return exitCode(findings);
    }

    // ---------------------------------------------------------------------------
    // Exit code
    // ---------------------------------------------------------------------------

    int exitCode(List<Finding> findings) {
        Optional<Severity> worst = findings.stream()
            .filter(f -> !f.isUnresolved())
            .map(Finding::riskLevel)
            .max(Comparator.comparingInt(Severity::ordinal));

        if (worst.isEmpty()) return 0;
        boolean breachesThreshold = worst.get().ordinal() >= threshold.ordinal();
        if (!breachesThreshold) return 0;

        return switch (failOn) {
            case FAIL  -> 1;
            case WARN  -> { out().println("[WARN] marshal: findings at or above threshold '" +
                threshold.name().toLowerCase() + "' detected."); yield 0; }
            case NEVER -> 0;
        };
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static RuleEngine buildEngine() {
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

    private static MavenCentralClient buildProductionClient(MarshalConfig config) {
        try {
            Path cacheDir = Paths.get(System.getProperty("user.home"), ".marshal");
            cacheDir.toFile().mkdirs();
            MetadataCache cache = new MetadataCache(cacheDir.resolve("metadata.db"));
            return new MavenCentralClient(cache);
        } catch (Exception e) {
            log.warn("Could not initialise metadata cache, running without cache: {}", e.getMessage());
            return new MavenCentralClient();
        }
    }

    private static Set<String> loadHighReputationGAs() {
        Set<String> gas = new HashSet<>();
        try (var in = ScanCommand.class.getClassLoader()
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

    private static boolean isUnresolved(Coordinates c) {
        return "UNRESOLVED".equals(c.version());
    }

    private static void acquire(Semaphore sem) {
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitAll(List<CompletableFuture<Void>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (CompletionException e) {
            log.warn("One or more parallel fetches failed: {}", e.getCause().getMessage());
        }
    }

    private static VersionMetadata stub(Coordinates coords) {
        return new VersionMetadata(coords, null, null, SignatureStatus.UNKNOWN,
            List.of(), -1, null, Instant.EPOCH, false);
    }

    private static PrintWriter out() {
        return new PrintWriter(System.out, true);
    }

    // ---------------------------------------------------------------------------
    // Enums
    // ---------------------------------------------------------------------------

    public enum OutputFormat { HUMAN, JSON, MD }

    public enum FailOn { FAIL, WARN, NEVER }

    // ---------------------------------------------------------------------------
    // Fallback reporter (used for JSON/MD until Blocks 3/4)
    // ---------------------------------------------------------------------------

    static class PlainTextReporter implements Reporter {
        @Override
        public void report(List<Finding> findings, PrintWriter out) {
            long flagged    = findings.stream().filter(f -> !f.isUnresolved() && f.riskLevel() != Severity.GREEN).count();
            long unresolved = findings.stream().filter(Finding::isUnresolved).count();
            long total      = findings.size();

            out.printf("marshal scan — %d dependencies%n", total);
            if (unresolved > 0) {
                out.printf("  %d could not be fully resolved — manual review recommended%n", unresolved);
            }

            findings.stream()
                .filter(f -> !f.isUnresolved() && f.riskLevel() != Severity.GREEN)
                .sorted(Comparator.comparingInt(Finding::riskScore).reversed())
                .forEach(f -> {
                    String from = f.fromVersion() != null ? f.fromVersion() + " → " : "";
                    out.printf("  [%s %d/100] %s %s%s%n",
                        f.riskLevel(), f.riskScore(),
                        f.coordinates().toGa(), from, f.toVersion());
                    f.signals().forEach(s ->
                        out.printf("    • %s (%d pts): %s%n",
                            s.severity(), s.scoreContribution(), s.evidence()));
                });

            out.printf("Summary: %d flagged of %d dependencies%n", flagged, total - unresolved);
        }
    }
}
