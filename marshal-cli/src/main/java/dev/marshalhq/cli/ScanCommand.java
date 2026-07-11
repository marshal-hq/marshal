package dev.marshalhq.cli;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.Finding;
import dev.marshalhq.core.RuleEngine;
import dev.marshalhq.core.Severity;
import dev.marshalhq.core.VersionMetadata;
import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.core.config.MarshalConfigLoader;
import dev.marshalhq.core.config.NotificationConfig;
import dev.marshalhq.core.whitelist.Whitelists;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.DependencyResolver;
import dev.marshalhq.resolvers.ResolutionException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "scan", description = "Scan a Maven or Gradle project for risky dependency updates.", mixinStandardHelpOptions = true) public class ScanCommand
        implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(ScanCommand.class);

    @Option(names = "--source", description = "Path to the pom.xml, build.gradle(.kts), or project directory to scan "
            + "(default: current directory). The build tool is detected from the path.")
    Path source;

    @Option(names = "--no-daemon", description = "Run Gradle with --no-daemon (one-shot/CI). Ignored for Maven scans.")
    boolean noDaemon = false;

    @Option(names = "--output", description = "Output format: human, json, md (default: human)", converter = CaseInsensitiveConverter.ForOutputFormat.class)
    OutputFormat outputFormat = OutputFormat.HUMAN;

    @Option(names = "--threshold", description = "Risk level that triggers failure: green, yellow, orange, red (default: red)", converter = CaseInsensitiveConverter.ForSeverity.class)
    Severity threshold = Severity.RED;

    @Option(names = "--fail-on", description = "Exit code behavior: fail, warn, never (default: fail)", converter = CaseInsensitiveConverter.ForFailOn.class)
    FailOn failOn = FailOn.FAIL;

    @Option(names = "--show-advisory", description = "Render YELLOW advisory findings in full detail (default: count only)")
    boolean showAdvisory = false;

    @Option(names = "--show-unresolved", description = "List each unresolved dependency by name (default: count only)")
    boolean showUnresolved = false;

    @Option(names = "--show-suppressed", description = "Render whitelist-suppressed findings in full detail (default: count only)")
    boolean showSuppressed = false;

    @Option(names = "--frozen-whitelist", description = "Use the embedded Marshal whitelist baseline only — no remote refresh (reproducible CI).")
    boolean frozenWhitelist = false;

    @Option(names = "--config", description = "Path to marshal.yml config file")
    Path configPath;

    @Option(names = "--cache-path", description = "Override default cache location (~/.marshal/metadata.db)")
    Path cachePath;

    @Option(names = "--slack-webhook", description = "Slack webhook URL. Overrides notifications.slack.webhook in marshal.yml.")
    String slackWebhookFlag = "";

    private final MavenCentralClient injectedClient;
    private final DependencyResolver injectedResolver;

    public ScanCommand() {
        this.injectedClient = null;
        this.injectedResolver = null;
    }

    /**
     * Package-private: inject components for testing.
     */
    ScanCommand(MavenCentralClient client, DependencyResolver resolver) {
        this.injectedClient = client;
        this.injectedResolver = resolver;
    }

    @Override
    public Integer call() {
        MarshalConfig config = MarshalConfigLoader.load(configPath);

        ResolverRouter.Routed routed = route(config);
        if (routed == null) {
            return 2;
        }
        DependencyResolver resolver = routed.resolver();
        Path target = routed.target();

        MavenCentralClient client = injectedClient != null ? injectedClient : CliHelper.buildProductionClient(cachePath);

        RuleEngine engine = CliHelper.buildEngine(config.getRules());
        Set<String> highReps = CliHelper.loadHighReputationGAs();

        List<Coordinates> allDeps;
        try {
            allDeps = resolver.resolve(target);
        }
        catch (ResolutionException e) {
            // Could-not-analyze is NOT all-clear: distinct exit code, message to stderr (S06).
            new PrintWriter(System.err, true).println("marshal: " + e.getMessage());
            return 3;
        }
        if (allDeps.isEmpty()) {
            new PrintWriter(System.out, true).println("No dependencies found in " + target);
            return 0;
        }

        Map<Boolean, List<Coordinates>> byResolution = allDeps.stream().collect(Collectors.partitioningBy(CliHelper::isUnresolved));

        List<Coordinates> resolved = byResolution.get(false);
        List<Coordinates> unresolved = byResolution.get(true);

        Semaphore semaphore = new Semaphore(24);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        // Step 1: version histories in parallel
        Map<String, List<String>> histories = createVersionHistories(resolved, semaphore, client, executor);

        // Step 2: identify previous version from history
        Map<String, Coordinates> previousCoords = identifyPreviousCoords(resolved, histories);

        // Step 3: fan-out metadata fetches (current + previous) in parallel
        // Packages with no version history are not on the registry — skip fetching entirely
        // so rules never fire on them (they receive the stub with UNKNOWN signature status).
        Map<String, VersionMetadata> metaByGav = prepareGavMetaData(resolved, histories, previousCoords, semaphore, client, executor);
        executor.shutdown();

        // Step 4: assemble findings. A dep whose descriptor could not be read is scored
        // normally but flagged: its subtree was never walked and must not look clean (S06).
        Set<String> unexpandedGas = resolver.unexpandedSubtrees().stream()
                .map(Coordinates::toGa)
                .collect(Collectors.toSet());
        List<Finding> findings = assembleCoordsFindings(resolved, metaByGav, previousCoords, engine, highReps, unresolved, unexpandedGas);

        // Step 4b: whitelist suppression.
        // A matched GAV stays in the audit record but drops out of the risk list, the exit code, and Slack.
        Whitelists whitelists = CliHelper.loadWhitelists(target, frozenWhitelist, cachePath);
        findings = CliHelper.applySuppression(findings, whitelists, LocalDate.now());

        // Step 5: classify first, then report
        ScanReport report = ScanReport.from(findings);
        writeReport(target, report);

        // Slack alert: CLI flag takes precedence over config; no-op when webhook is blank
        publishAlerts(config, findings);

        return CliHelper.computeExitCode(report, threshold, failOn);
    }

    private void publishAlerts(MarshalConfig config, List<Finding> findings) {
        NotificationConfig.SlackConfig slack = config.getNotifications().getSlack();
        String effectiveWebhook = !slackWebhookFlag.isBlank() ? slackWebhookFlag : slack.getWebhook();
        Severity slackMinLevel = CliHelper.parseLevel(slack.getMinLevel(), Severity.RED);
        new SlackNotifier().notify(findings, effectiveWebhook, slackMinLevel);
    }

    private void writeReport(Path target, ScanReport report) {
        PrintWriter writer = new PrintWriter(System.out, true);
        Reporter reporter = switch (outputFormat) {
            case HUMAN -> new TerminalReporter(showAdvisory, showUnresolved, showSuppressed);
            case JSON -> new JsonReporter(target.toString(), Instant.now());
            case MD -> new MarkdownReporter(showAdvisory, showUnresolved);
        };
        reporter.report(report, writer);
        writer.flush();
    }

    private static List<Finding> assembleCoordsFindings(List<Coordinates> resolved,
            Map<String, VersionMetadata> metaByGav,
            Map<String, Coordinates> previousCoords,
            RuleEngine engine,
            Set<String> highReps,
            List<Coordinates> unresolved,
            Set<String> unexpandedGas) {

        List<Finding> findings = new ArrayList<>();
        for (Coordinates coords : resolved) {
            VersionMetadata current = metaByGav.getOrDefault(coords.toGav(), CliHelper.stub(coords));
            Coordinates prevCoords = previousCoords.get(coords.toGav());
            VersionMetadata previous = prevCoords != null ? metaByGav.get(prevCoords.toGav()) : null;
            String fromVersion = prevCoords != null ? prevCoords.version() : null;
            Finding finding = CliHelper.toFinding(coords, fromVersion, current, previous, engine, highReps);
            if (unexpandedGas.contains(coords.toGa())) {
                finding = finding.withUnexpandedSubtree();
            }
            findings.add(finding);
        }
        for (Coordinates coords : unresolved) {
            findings.add(Finding.unresolved(coords));
        }
        return findings;
    }

    private static Map<String, VersionMetadata> prepareGavMetaData(List<Coordinates> resolved,
            Map<String, List<String>> histories,
            Map<String, Coordinates> previousCoords,
            Semaphore semaphore,
            MavenCentralClient client,
            ExecutorService executor) {

        Map<String, VersionMetadata> metaByGav = new ConcurrentHashMap<>();
        List<Coordinates> toFetch = new ArrayList<>(
                resolved.stream().filter(c -> !histories.getOrDefault(c.toGa(), List.of()).isEmpty()).toList());
        toFetch.addAll(previousCoords.values());
        CliHelper.awaitAll(toFetch.stream().map(coords -> CompletableFuture.runAsync(() -> {
            CliHelper.acquire(semaphore);
            try {
                metaByGav.put(coords.toGav(), client.fetchMetadata(coords));
            }
            finally {
                semaphore.release();
            }
        }, executor)).toList());
        return metaByGav;
    }

    private static Map<String, Coordinates> identifyPreviousCoords(List<Coordinates> resolved,
            Map<String, List<String>> histories) {

        Map<String, Coordinates> previousCoords = new HashMap<>();
        for (Coordinates coords : resolved) {
            List<String> history = histories.getOrDefault(coords.toGa(), List.of());
            int idx = history.indexOf(coords.version());
            if (idx >= 0 && idx + 1 < history.size()) {
                previousCoords.put(coords.toGav(), new Coordinates(coords.groupId(), coords.artifactId(), history.get(idx + 1)));
            }
        }
        return previousCoords;
    }

    private static Map<String, List<String>> createVersionHistories(List<Coordinates> resolved,
            Semaphore semaphore,
            MavenCentralClient client,
            ExecutorService executor) {

        ConcurrentHashMap<String, List<String>> histories = new ConcurrentHashMap<>();
        CliHelper.awaitAll(resolved.stream().map(coordinates -> CompletableFuture.runAsync(() -> {
            CliHelper.acquire(semaphore);
            try {
                histories.put(coordinates.toGa(), client.getVersionHistory(coordinates.groupId(), coordinates.artifactId()));
            }
            finally {
                semaphore.release();
            }
        }, executor)).toList());
        return histories;
    }

    /**
     * Selects the resolver and target path from {@code --source} (default: the current
     * directory). Honors an injected resolver (tests); otherwise the build tool is
     * detected from the path via the shared {@link ResolverRouter}. Returns {@code null}
     * after printing an error when selection fails.
     */
    private ResolverRouter.Routed route(MarshalConfig config) {
        PrintWriter err = new PrintWriter(System.err, true);
        Path target = source != null ? source : Path.of(".");
        if (injectedResolver != null) {
            return new ResolverRouter.Routed(injectedResolver, target);
        }
        return ResolverRouter.forPath(target, config, noDaemon, err);
    }
}
