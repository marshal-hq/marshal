package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.registry.MetadataCache;

import java.nio.file.Path;
import java.util.List;

/**
 * Populates the demo cache by fetching real metadata from Maven Central.
 * Run before the demo; cache entries are valid for 24 h.
 *
 * Usage (from repo root after ./gradlew :marshal-cli:shadowJar):
 *   java -cp marshal-cli/build/libs/marshal-cli-*.jar dev.marshalhq.cli.DemoCacheBuilder [cache-path]
 *
 * Default path: examples/demo/marshal-cache.db
 */
public class DemoCacheBuilder {

    private static final List<Coordinates> DEMO_DEPS = List.of(
        new Coordinates("commons-logging",      "commons-logging",      "1.3.4"),
        new Coordinates("javax.inject",          "javax.inject",          "1"),
        new Coordinates("commons-collections",   "commons-collections",   "3.2.2"),
        new Coordinates("log4j",                 "log4j",                 "1.2.17")
    );

    public static void main(String[] args) throws Exception {
        String dbPath = args.length > 0 ? args[0] : "examples/demo/marshal-cache.db";
        Path.of(dbPath).toAbsolutePath().getParent().toFile().mkdirs();

        System.err.println("Fetching metadata from Maven Central...");
        System.err.println();

        MetadataCache cache = new MetadataCache(Path.of(dbPath));
        MavenCentralClient client = new MavenCentralClient(cache);
        RuleEngine engine = CliHelper.buildEngine();

        for (Coordinates coords : DEMO_DEPS) {
            List<String> history = client.getVersionHistory(coords.groupId(), coords.artifactId());

            int idx = history.indexOf(coords.version());
            Coordinates prevCoords = (idx >= 0 && idx + 1 < history.size())
                ? new Coordinates(coords.groupId(), coords.artifactId(), history.get(idx + 1))
                : null;

            VersionMetadata current  = client.fetchMetadata(coords);
            VersionMetadata previous = prevCoords != null ? client.fetchMetadata(prevCoords) : null;

            List<VersionMetadata> historyMeta = previous != null ? List.of(previous) : List.of();
            PackageContext ctx = new PackageContext(coords, current, previous, historyMeta, null, false);
            RuleEngine.EvaluationDetail detail = engine.evaluateWithDetails(ctx);

            String from = prevCoords != null ? prevCoords.version() : "(no previous)";
            String rules = detail.firedRules().isEmpty() ? "none"
                : detail.firedRules().stream()
                    .map(r -> r.ruleId() + "(" + r.scoreContribution() + ")")
                    .reduce((a, b) -> a + " + " + b).orElse("");

            System.out.printf("  [%-6s %3d] %-48s  %s -> %s  [%s]%n",
                detail.score().level(),
                detail.score().score(),
                coords.toGa(),
                from, coords.version(),
                rules);
        }

        cache.close();
        System.err.println();
        System.err.println("Cache written to: " + dbPath);
        System.err.println("Valid for 24 h. Run scan --cache-path " + dbPath + " to verify.");
    }
}
