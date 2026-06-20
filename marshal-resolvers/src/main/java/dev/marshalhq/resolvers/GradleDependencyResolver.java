package dev.marshalhq.resolvers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.marshalhq.core.Coordinates;

/**
 * Resolves Gradle project dependencies by shelling out to the project's own Gradle
 * wrapper with an injected init script ({@code marshal-init.gradle}). The init
 * script walks the resolved dependency graph and writes the full external-module
 * set to a temp JSON file, which is parsed here into the shared {@link Coordinates}
 * type — identical to what {@link PomDependencyResolver} emits.
 *
 * <p>Unlike the Maven path (direct-only, S13), Gradle resolution returns the full
 * transitive set with versions resolved post conflict resolution. v1 collapses to
 * unique {@code group:artifact:version} coordinates; the direct/transitive and
 * configuration metadata the init script emits is intentionally not surfaced
 * (would require changes outside marshal-resolvers).
 */
public class GradleDependencyResolver implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(GradleDependencyResolver.class);
    private static final Set<DependencyScope> DEFAULT_SCOPES =
            EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME);
    private static final String INIT_SCRIPT_RESOURCE = "/marshal-init.gradle";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    private static final int STDERR_TAIL = 4000;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /**
     * Resolves the Gradle executable for a project. Extracted as a seam so the
     * no-wrapper / {@code gradle}-on-PATH fallback is unit-testable without a real
     * wrapper or a real PATH ({@link #DEFAULT_LOCATOR} is the production behavior).
     */
    @FunctionalInterface
    interface GradleLocator {
        String locate(Path scanRoot);
    }

    /** Production locator: the project's wrapper if present, else {@code gradle} on PATH. */
    static final GradleLocator DEFAULT_LOCATOR = GradleDependencyResolver::defaultLocate;

    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean includeTest;
    private final boolean noDaemon;
    private final Duration timeout;
    private final GradleLocator locator;

    public GradleDependencyResolver() {
        this(DEFAULT_SCOPES, false);
    }

    public GradleDependencyResolver(Collection<DependencyScope> includedScopes) {
        this(includedScopes, false);
    }

    public GradleDependencyResolver(Collection<DependencyScope> includedScopes, boolean noDaemon) {
        this(includedScopes, noDaemon, DEFAULT_TIMEOUT);
    }

    /** Package-private: lets tests inject a short timeout for the hang case. */
    GradleDependencyResolver(Collection<DependencyScope> includedScopes, boolean noDaemon,
            Duration timeout) {
        this(includedScopes, noDaemon, timeout, DEFAULT_LOCATOR);
    }

    /** Package-private: lets tests inject the Gradle locator (PATH-fallback case). */
    GradleDependencyResolver(Collection<DependencyScope> includedScopes, boolean noDaemon,
            Duration timeout, GradleLocator locator) {
        this.includeTest = includedScopes.contains(DependencyScope.TEST);
        this.noDaemon = noDaemon;
        this.timeout = timeout;
        this.locator = locator;
    }

    /**
     * @param buildFile path to {@code build.gradle} / {@code build.gradle.kts}, or the
     *                  project directory containing it.
     * @return unique resolved coordinates (possibly empty for a project that genuinely
     *         declares none).
     * @throws ResolutionException if the project could not be analyzed at all — no
     *         Gradle found, build failure, or timeout. Never returns clean on failure (S06).
     */
    @Override
    public List<Coordinates> resolve(Path buildFile) {
        Path scanRoot = Files.isDirectory(buildFile) ? buildFile : buildFile.getParent();
        if (scanRoot == null) {
            scanRoot = Path.of(".");
        }

        String gradleCmd = locator.locate(scanRoot);

        Path initScript = null;
        Path outJson = null;
        try {
            initScript = writeInitScript();
            outJson = Files.createTempFile("marshal-gradle-deps", ".json");
            runGradle(gradleCmd, scanRoot, initScript, outJson);
            return parse(outJson, scanRoot);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ResolutionException(
                    "could not resolve dependencies for " + scanRoot + ": " + e.getMessage());
        } finally {
            deleteQuietly(initScript);
            deleteQuietly(outJson);
        }
    }

    private static String defaultLocate(Path scanRoot) {
        String wrapperName = IS_WINDOWS ? "gradlew.bat" : "gradlew";
        Path wrapper = scanRoot.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) {
            return wrapper.toAbsolutePath().toString();
        }
        log.warn("No Gradle wrapper at {}; falling back to 'gradle' on PATH", scanRoot);
        return IS_WINDOWS ? "gradle.bat" : "gradle";
    }

    private void runGradle(String gradleCmd, Path scanRoot, Path initScript, Path outJson)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.command().add(gradleCmd);
        pb.command().add("--init-script");
        pb.command().add(initScript.toAbsolutePath().toString());
        pb.command().add("marshalDeps");
        pb.command().add("-PmarshalOut=" + outJson.toAbsolutePath());
        if (includeTest) {
            pb.command().add("-PmarshalIncludeTest=true");
        }
        pb.command().add("--quiet");
        pb.command().add("--console=plain");
        if (noDaemon) {
            pb.command().add("--no-daemon");
        }
        pb.directory(scanRoot.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start();
        // Drain stderr on a separate thread: a blocking readAllBytes() here would never
        // return for a hung build, so the waitFor timeout below would never fire.
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new ResolutionException("could not resolve dependencies for " + scanRoot
                    + ": Gradle timed out after " + timeout.toSeconds() + "s");
        }
        int exit = process.exitValue();
        if (exit != 0) {
            byte[] stderr;
            try {
                stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                stderr = new byte[0];
            }
            log.error("Gradle resolution for {} failed (exit {}):\n{}",
                    scanRoot, exit, truncate(new String(stderr, StandardCharsets.UTF_8)));
            throw new ResolutionException("could not resolve dependencies for " + scanRoot
                    + ": Gradle build failed (exit " + exit + ", see stderr above)");
        }
    }

    private Path writeInitScript() throws IOException {
        Path tmp = Files.createTempFile("marshal-init", ".gradle");
        try (InputStream in = GradleDependencyResolver.class.getResourceAsStream(INIT_SCRIPT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled init script " + INIT_SCRIPT_RESOURCE + " not found on classpath");
            }
            Files.write(tmp, in.readAllBytes());
        }
        return tmp;
    }

    private List<Coordinates> parse(Path outJson, Path scanRoot) throws IOException {
        // Gradle exited 0 but wrote no output file at all. The init script ALWAYS
        // writes JSON (even "[]") when marshalDeps runs, so a missing file means the
        // task never ran — that is "could not analyze", not a clean empty result (S06).
        if (!Files.isRegularFile(outJson) || Files.size(outJson) == 0) {
            throw new ResolutionException("could not resolve dependencies for " + scanRoot
                    + ": Gradle exited 0 but produced no dependency output"
                    + " (the marshalDeps task did not run)");
        }
        JsonNode root = mapper.readTree(outJson.toFile());
        // Dedupe to unique group:artifact:version, preserving first-seen order.
        Map<String, Coordinates> unique = new LinkedHashMap<>();
        for (JsonNode node : root) {
            String group = text(node, "group");
            String name = text(node, "name");
            String version = text(node, "version");
            if (group == null || name == null || version == null) {
                continue;
            }
            Coordinates c = new Coordinates(group, name, version);
            unique.putIfAbsent(c.toGav(), c);
        }
        return List.copyOf(unique.values());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s) {
        if (s.length() <= STDERR_TAIL) {
            return s;
        }
        return "…(truncated)…\n" + s.substring(s.length() - STDERR_TAIL);
    }

    private static void deleteQuietly(Path p) {
        if (p == null) {
            return;
        }
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("Could not delete temp file {}: {}", p, e.getMessage());
        }
    }
}
