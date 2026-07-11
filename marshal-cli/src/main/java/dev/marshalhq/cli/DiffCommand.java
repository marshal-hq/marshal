package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.core.config.MarshalConfigLoader;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.DependencyResolver;
import dev.marshalhq.resolvers.ResolutionException;
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
 * marshal diff — compare two project states and report only new dependency risks.
 * <p>
 * Build-tool-agnostic: {@code --base} and {@code --head} each accept a path that is
 * either a build file or a project directory, routed independently through
 * {@link ResolverRouter} exactly like {@code scan}. The two sides may even use
 * different build tools (e.g. Maven base, Gradle head) — the delta is computed at
 * the coordinate level.
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
        description = "Compare two project states and report new dependency risks.",
        mixinStandardHelpOptions = true
)
public class DiffCommand implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(DiffCommand.class);

    @Option(names = "--base", description = "Path to the base build file or project directory",
            required = true)
    Path basePom;

    @Option(names = "--head", description = "Path to the head build file or project directory",
            required = true)
    Path headPom;

    @Option(names = "--no-daemon",
            description = "Run Gradle with --no-daemon (one-shot/CI). Ignored for Maven.")
    boolean noDaemon = false;

    @Option(names = "--output", description = "Output format: human, json, md (default: md)",
            converter = CaseInsensitiveConverter.ForOutputFormat.class)
    OutputFormat outputFormat = OutputFormat.MD;

    @Option(names = "--threshold", description = "Risk level that triggers failure (default: red)",
            converter = CaseInsensitiveConverter.ForSeverity.class)
    Severity threshold = Severity.RED;

    @Option(names = "--fail-on", description = "Exit code behavior: fail, warn, never (default: fail)",
            converter = CaseInsensitiveConverter.ForFailOn.class)
    FailOn failOn = FailOn.FAIL;

    @Option(names = "--show-advisory", description = "Render YELLOW advisory findings in full detail (default: count only)")
    boolean showAdvisory = false;

    @Option(names = "--show-unresolved", description = "List each unresolved dependency by name (default: count only)")
    boolean showUnresolved = false;

    @Option(names = "--show-suppressed", description = "Render whitelist-suppressed findings in full detail (default: count only)")
    boolean showSuppressed = false;

    @Option(names = "--frozen-whitelist",
            description = "Use the embedded Marshal whitelist baseline only — no remote refresh (reproducible CI).")
    boolean frozenWhitelist = false;

    @Option(names = "--config", description = "Path to marshal.yml config file")
    Path configPath;

    @Option(names = "--cache-path", description = "Override default cache location (~/.marshal/metadata.db)")
    Path cachePath;

    @Option(names = "--slack-webhook",
            description = "Slack webhook URL. Overrides notifications.slack.webhook in marshal.yml.")
    String slackWebhookFlag = "";

    private final MavenCentralClient injectedClient;
    private final DependencyResolver injectedResolver;

    public DiffCommand() {
        this.injectedClient = null;
        this.injectedResolver = null;
    }

    /**
     * Package-private: inject components for testing. The injected resolver is used
     * for both sides.
     */
    DiffCommand(MavenCentralClient client, DependencyResolver resolver) {
        this.injectedClient = client;
        this.injectedResolver = resolver;
    }

    @Override
    public Integer call() {
        MarshalConfig config = MarshalConfigLoader.load(configPath);
        PrintWriter err = new PrintWriter(System.err, true);

        // Route each side independently (file or directory; Maven or Gradle), exactly
        // like scan. The two sides may use different build tools.
        ResolverRouter.Routed baseRoute = resolveSide(basePom, config, err);
        ResolverRouter.Routed headRoute = resolveSide(headPom, config, err);
        if (baseRoute == null || headRoute == null) {
            return 2;
        }

        MavenCentralClient client = injectedClient != null
                ? injectedClient : CliHelper.buildProductionClient(cachePath);

        RuleEngine engine = CliHelper.buildEngine(config.getRules());
        Set<String> highReps = CliHelper.loadHighReputationGAs();

        // Honesty invariant (§2.4): if EITHER side fails to resolve, the diff is
        // untrustworthy. Exit 3 — never diff against a partial/empty set, which would
        // false-clean (head fails) or flag every head dep as new (base fails).
        Map<String, Coordinates> baseMap;
        try {
            baseMap = toGaMap(baseRoute.resolver().resolve(baseRoute.target()));
        } catch (ResolutionException e) {
            err.println("marshal: could not resolve base for comparison: " + e.getMessage());
            return 3;
        }
        Map<String, Coordinates> headMap;
        try {
            headMap = toGaMap(headRoute.resolver().resolve(headRoute.target()));
        } catch (ResolutionException e) {
            err.println("marshal: could not resolve head for comparison: " + e.getMessage());
            return 3;
        }

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

        // Whitelist suppression. A matched GAV stays in the audit record but drops out of
        // the risk list, the exit code, and Slack. Resolved relative to the head.
        findings = CliHelper.applySuppression(findings,
                CliHelper.loadWhitelists(headPom, frozenWhitelist, cachePath), java.time.LocalDate.now());

        // Report
        ScanReport report = ScanReport.from(findings);
        PrintWriter writer = new PrintWriter(System.out, true);
        Reporter reporter = switch (outputFormat) {
            case HUMAN -> new TerminalReporter(showAdvisory, showUnresolved, showSuppressed);
            case JSON -> new JsonReporter(headPom.toString(), Instant.now());
            case MD -> new MarkdownReporter(showAdvisory, showUnresolved);
        };
        reporter.report(report, writer);
        writer.flush();

        String effectiveWebhook = !slackWebhookFlag.isBlank()
                ? slackWebhookFlag
                : config.getNotifications().getSlack().getWebhook();
        Severity slackMinLevel = CliHelper.parseLevel(
                config.getNotifications().getSlack().getMinLevel(), Severity.RED);
        new SlackNotifier().notify(findings, effectiveWebhook, slackMinLevel);

        return CliHelper.computeExitCode(report, threshold, failOn);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private ResolverRouter.Routed resolveSide(Path path, MarshalConfig config, PrintWriter err) {
        if (injectedResolver != null) {
            return new ResolverRouter.Routed(injectedResolver, path);
        }
        return ResolverRouter.forPath(path, config, noDaemon, err);
    }

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
