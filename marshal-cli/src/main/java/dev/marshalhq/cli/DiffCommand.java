package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.core.config.MarshalConfigLoader;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * marshal diff — compare two POM files and report only new dependency risks.
 * <p>
 * Classification:
 * ADDED          → dep in head only → evaluated (previous = null)
 * VERSION_CHANGED → same GA, different version → evaluated (previous = base version)
 * REMOVED        → dep in base only → no finding (removal is not a new risk)
 * UNCHANGED      → same GA, same version → no finding
 * <p>
 * No version-history lookup is needed: the base version IS the previous version.
 * Reuses all reporters from Blocks 2–4.
 */
@Command(
        name = "diff",
        description = "Compare two POM files and report new dependency risks.",
        mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(DiffCommand.class);

    @Option(names = "--base", description = "Path to the base pom.xml", required = true)
    Path basePom;

    @Option(names = "--head", description = "Path to the head pom.xml", required = true)
    Path headPom;

    @Option(names = "--output", description = "Output format: human, json, md (default: md)",
            converter = CaseInsensitiveConverter.ForOutputFormat.class)
    OutputFormat outputFormat = OutputFormat.MD;

    @Option(names = "--threshold", description = "Risk level that triggers failure (default: red)",
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

    public DiffCommand() {
        this.injectedClient = null;
        this.injectedResolver = null;
    }

    /**
     * Package-private: inject components for testing.
     */
    DiffCommand(MavenCentralClient client, PomDependencyResolver resolver) {
        this.injectedClient = client;
        this.injectedResolver = resolver;
    }

    @Override
    public Integer call() {
        MarshalConfig config = MarshalConfigLoader.load(configPath);
        PomDependencyResolver resolver = injectedResolver != null
                ? injectedResolver : new PomDependencyResolver();
        MavenCentralClient client = injectedClient != null
                ? injectedClient : CliHelper.buildProductionClient(cachePath);

        RuleEngine engine = CliHelper.buildEngine();
        Set<String> highReps = CliHelper.loadHighReputationGAs();

        // Resolve both POMs, keyed by GA
        Map<String, Coordinates> baseMap = toGaMap(resolver.resolve(basePom));
        Map<String, Coordinates> headMap = toGaMap(resolver.resolve(headPom));

        // Classify
        List<Coordinates> added = new ArrayList<>();
        List<DiffPair> changed = new ArrayList<>();

        for (Map.Entry<String, Coordinates> e : headMap.entrySet()) {
            Coordinates head = e.getValue();
            Coordinates base = baseMap.get(e.getKey());
            if (base == null) {
                added.add(head);
            }
            else if (!base.version().equals(head.version())) {
                changed.add(new DiffPair(base, head));
            }
            // UNCHANGED — no finding
        }
        // REMOVED (in base, not in head) — no finding

        // Fan-out metadata fetches in parallel (Semaphore 24)
        Semaphore semaphore = new Semaphore(24);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ConcurrentHashMap<String, VersionMetadata> metaByGav = new ConcurrentHashMap<>();

        List<Coordinates> toFetch = new ArrayList<>(added);
        for (DiffPair p : changed) {
            toFetch.add(p.head());
            toFetch.add(p.base());
        }

        CliHelper.awaitAll(toFetch.stream()
                .filter(c -> !CliHelper.isUnresolved(c))
                .map(c -> CompletableFuture.runAsync(() -> {
                    CliHelper.acquire(semaphore);
                    try {
                        metaByGav.put(c.toGav(), client.fetchMetadata(c));
                    }
                    finally {
                        semaphore.release();
                    }
                }, executor))
                .toList());
        executor.shutdown();

        // Assemble findings
        List<Finding> findings = new ArrayList<>();

        for (Coordinates head : added) {
            if (CliHelper.isUnresolved(head)) {
                findings.add(Finding.unresolved(head));
                continue;
            }
            VersionMetadata current = metaByGav.getOrDefault(head.toGav(), CliHelper.stub(head));
            findings.add(CliHelper.toFinding(head, null, current, null, engine, highReps));
        }

        for (DiffPair pair : changed) {
            Coordinates head = pair.head();
            if (CliHelper.isUnresolved(head)) {
                findings.add(Finding.unresolved(head));
                continue;
            }
            VersionMetadata current = metaByGav.getOrDefault(head.toGav(), CliHelper.stub(head));
            VersionMetadata previous = CliHelper.isUnresolved(pair.base()) ? null
                    : metaByGav.get(pair.base().toGav());
            findings.add(CliHelper.toFinding(head, pair.base().version(), current, previous, engine, highReps));
        }

        // Report
        PrintWriter writer = new PrintWriter(System.out, true);
        Reporter reporter = switch (outputFormat) {
            case HUMAN -> new TerminalReporter();
            case JSON -> new JsonReporter(headPom.toString(), Instant.now());
            case MD -> new MarkdownReporter();
        };
        reporter.report(findings, writer);
        writer.flush();

        String effectiveWebhook = !slackWebhookFlag.isBlank()
                ? slackWebhookFlag
                : config.getNotifications().getSlack().getWebhook();
        Severity slackMinLevel = CliHelper.parseLevel(
                config.getNotifications().getSlack().getMinLevel(), Severity.RED);
        new SlackNotifier().notify(findings, effectiveWebhook, slackMinLevel);

        return CliHelper.computeExitCode(findings, threshold, failOn);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static Map<String, Coordinates> toGaMap(List<Coordinates> deps) {
        Map<String, Coordinates> map = new LinkedHashMap<>();
        for (Coordinates c : deps) {
            map.put(c.toGa(), c);
        }
        return map;
    }

    private record DiffPair(Coordinates base, Coordinates head) {

    }
}
