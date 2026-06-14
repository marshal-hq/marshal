package dev.marshalhq.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.RuleEngine;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.VersionMetadata;
import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.core.config.MarshalConfigLoader;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.DependencyScope;
import dev.marshalhq.resolvers.PomDependencyResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "scan",
        description = "Scan a POM file for risky dependency updates.",
        mixinStandardHelpOptions = true
)
public class ScanCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);

    @Option(names = "--pom", description = "Path to the pom.xml to scan", required = true)
    Path pomPath;

    @Option(names = "--output", description = "Output format: human, json, md (default: human)",
            converter = CaseInsensitiveConverter.ForOutputFormat.class)
    OutputFormat outputFormat = OutputFormat.HUMAN;

    @Option(names = "--threshold", description = "Risk level that triggers failure: green, yellow, orange, red (default: red)",
            converter = CaseInsensitiveConverter.ForSeverity.class)
    Severity threshold = Severity.RED;

    @Option(names = "--fail-on", description = "Exit code behavior: fail, warn, never (default: fail)",
            converter = CaseInsensitiveConverter.ForFailOn.class)
    FailOn failOn = FailOn.FAIL;

    @Option(names = "--config", description = "Path to marshal.yml config file")
    Path configPath;

    @Option(names = "--cache-path", description = "Override default cache location (~/.marshal/metadata.db)")
    Path cachePath;

    @Option(names = "--slack-webhook",
            description = "Slack webhook URL. Overrides notifications.slack.webhook in marshal.yml.")
    String slackWebhookFlag = "";

    private final MavenCentralClient injectedClient;
    private final PomDependencyResolver injectedResolver;

    public ScanCommand() {
        this.injectedClient = null;
        this.injectedResolver = null;
    }

    /**
     * Package-private: inject components for testing.
     */
    ScanCommand(MavenCentralClient client, PomDependencyResolver resolver) {
        this.injectedClient = client;
        this.injectedResolver = resolver;
    }

    @Override
    public Integer call() {
        MarshalConfig config = MarshalConfigLoader.load(configPath);
        PomDependencyResolver resolver = injectedResolver != null
                ? injectedResolver : new PomDependencyResolver(
                        DependencyScope.fromNames(config.getScan().getScopes()));
        MavenCentralClient client = injectedClient != null
                ? injectedClient : CliHelper.buildProductionClient(cachePath);

        RuleEngine engine = CliHelper.buildEngine();
        Set<String> highReps = CliHelper.loadHighReputationGAs();

        List<Coordinates> allDeps = resolver.resolve(pomPath);
        if (allDeps.isEmpty()) {
            new PrintWriter(System.out, true).println("No dependencies found in " + pomPath);
            return 0;
        }

        List<Coordinates> resolved = allDeps.stream().filter(c -> !CliHelper.isUnresolved(c)).toList();
        List<Coordinates> unresolved = allDeps.stream().filter(CliHelper::isUnresolved).toList();

        Semaphore semaphore = new Semaphore(24);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Step 1: version histories in parallel
        ConcurrentHashMap<String, List<String>> histories = new ConcurrentHashMap<>();
        CliHelper.awaitAll(resolved.stream().map(coordinates ->
                CompletableFuture.runAsync(() -> {
                    CliHelper.acquire(semaphore);
                    try {
                        histories.put(coordinates.toGa(), client.getVersionHistory(coordinates.groupId(), coordinates.artifactId()));
                    }
                    finally {
                        semaphore.release();
                    }
                }, executor)
        ).toList());

        // Step 2: identify previous version from history
        Map<String, Coordinates> previousCoords = new HashMap<>();
        for (Coordinates coords : resolved) {
            List<String> history = histories.getOrDefault(coords.toGa(), List.of());
            int idx = history.indexOf(coords.version());
            if (idx >= 0 && idx + 1 < history.size()) {
                previousCoords.put(coords.toGav(),
                        new Coordinates(coords.groupId(), coords.artifactId(), history.get(idx + 1)));
            }
        }

        // Step 3: fan-out metadata fetches (current + previous) in parallel
        ConcurrentHashMap<String, VersionMetadata> metaByGav = new ConcurrentHashMap<>();
        List<Coordinates> toFetch = new ArrayList<>(resolved);
        previousCoords.values().forEach(toFetch::add);
        CliHelper.awaitAll(toFetch.stream().map(coords ->
                CompletableFuture.runAsync(() -> {
                    CliHelper.acquire(semaphore);
                    try {
                        metaByGav.put(coords.toGav(), client.fetchMetadata(coords));
                    }
                    finally {
                        semaphore.release();
                    }
                }, executor)
        ).toList());
        executor.shutdown();

        // Step 4: assemble findings
        List<Finding> findings = new ArrayList<>();
        for (Coordinates coords : resolved) {
            VersionMetadata current = metaByGav.getOrDefault(coords.toGav(), CliHelper.stub(coords));
            Coordinates prevCoords = previousCoords.get(coords.toGav());
            VersionMetadata previous = prevCoords != null ? metaByGav.get(prevCoords.toGav()) : null;
            String fromVersion = prevCoords != null ? prevCoords.version() : null;
            findings.add(CliHelper.toFinding(coords, fromVersion, current, previous, engine, highReps));
        }
        for (Coordinates coords : unresolved) {
            findings.add(Finding.unresolved(coords));
        }

        // Step 5: report
        PrintWriter writer = new PrintWriter(System.out, true);
        Reporter reporter = switch (outputFormat) {
            case HUMAN -> new TerminalReporter();
            case JSON -> new JsonReporter(pomPath.toString(), Instant.now());
            case MD -> new MarkdownReporter();
        };
        reporter.report(findings, writer);
        writer.flush();

        // Slack alert — CLI flag takes precedence over config; no-op when webhook is blank
        String effectiveWebhook = !slackWebhookFlag.isBlank()
                ? slackWebhookFlag
                : config.getNotifications().getSlack().getWebhook();
        Severity slackMinLevel = CliHelper.parseLevel(
                config.getNotifications().getSlack().getMinLevel(), Severity.RED);
        new SlackNotifier().notify(findings, effectiveWebhook, slackMinLevel);

        return CliHelper.computeExitCode(findings, threshold, failOn);
    }

    // Fallback reporter used until Block 3/4 reporters were implemented — kept for completeness.
    static class PlainTextReporter implements Reporter {

        @Override
        public void report(List<Finding> findings, PrintWriter out) {
            long flagged = findings.stream()
                    .filter(f -> !f.isUnresolved() && f.riskLevel() != Severity.GREEN).count();
            long unresolved = findings.stream().filter(Finding::isUnresolved).count();
            out.printf("marshal scan — %d dependencies%n", findings.size());
            if (unresolved > 0) {
                out.printf("  %d could not be fully resolved — manual review recommended%n", unresolved);
            }
            findings.stream()
                    .filter(f -> !f.isUnresolved() && f.riskLevel() != Severity.GREEN)
                    .sorted(Comparator.comparingInt(Finding::riskScore).reversed())
                    .forEach(f -> {
                        String from = f.fromVersion() != null ? f.fromVersion() + " → " : "";
                        out.printf("  [%s %d/100] %s %s%s%n",
                                f.riskLevel(), f.riskScore(), f.coordinates().toGa(), from, f.toVersion());
                    });
            out.printf("Summary: %d flagged%n", flagged);
        }
    }
}
