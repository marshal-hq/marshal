package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.DependencyScope;
import dev.marshalhq.resolvers.GradleDependencyResolver;
import dev.marshalhq.resolvers.PomDependencyResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Fixture scoring for a fixed dependency graph, driven through the REAL dependency
 * resolvers (no resolver mocking) with only the {@link MavenCentralClient} seeded so
 * scoring is deterministic and offline:
 *
 * <pre>
 *   project
 *   ├── dep-a  ──> dep-x
 *   └── dep-b  ──> dep-x, dep-y
 *   dep-x  ──> dep-z
 * </pre>
 *
 * <p>Both build tools now walk the full transitive graph, so the Maven and Gradle runs
 * assert the <b>same</b> result via {@link #assertFullGraphScores}: the flattened,
 * de-duplicated set {@code [dep-a, dep-b, dep-x, dep-y, dep-z]} (dep-x pinned to one
 * version, deduped to a single node).
 *
 * <p>Determinism seams: the Maven resolver walks a committed local file-repo; the Gradle
 * resolver runs a real Gradle build against the same shape of repo. Neither touches the
 * network. The client is seeded so the scores are pinned:
 * dep-x → 0 (signed, first-seen), dep-y → 25 (DEP-EXPLOSION, 5→16 deps),
 * dep-z → 15 (MISSING-SIG, unsigned first-seen); dep-a/dep-b → 0.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphResolverScoringIT {

    @Mock
    MavenCentralClient mockClient;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP = "com.example.marshaltest";

    private static final Coordinates DEP_A = new Coordinates(GROUP, "dep-a", "1.0.0");
    private static final Coordinates DEP_B = new Coordinates(GROUP, "dep-b", "1.0.0");
    private static final Coordinates DEP_X = new Coordinates(GROUP, "dep-x", "1.0.0");
    private static final Coordinates DEP_Y = new Coordinates(GROUP, "dep-y", "1.0.0");
    private static final Coordinates DEP_Y_PREV = new Coordinates(GROUP, "dep-y", "0.9.0");
    private static final Coordinates DEP_Z = new Coordinates(GROUP, "dep-z", "1.0.0");

    // ── System.out capture ──────────────────────────────────────────────────────
    private PrintStream origOut;
    private ByteArrayOutputStream outBytes;

    @BeforeEach
    void redirect() {
        origOut = System.out;
        outBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes));
    }

    @AfterEach
    void restore() {
        System.setOut(origOut);
    }

    // ── seeded metadata (scoring only — the resolver is real) ────────────────────

    private static VersionMetadata signed(Coordinates c, int depCount) {
        return new VersionMetadata(c, null, "AABBCC", SignatureStatus.PRESENT,
                List.of(), depCount, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata unsigned(Coordinates c, int depCount) {
        return new VersionMetadata(c, null, null, SignatureStatus.ABSENT,
                List.of(), depCount, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private void seedClient() {
        // dep-a / dep-b: signed, first-seen → 0
        when(mockClient.getVersionHistory(GROUP, "dep-a")).thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(DEP_A)).thenReturn(signed(DEP_A, 5));
        when(mockClient.getVersionHistory(GROUP, "dep-b")).thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(DEP_B)).thenReturn(signed(DEP_B, 5));

        // dep-x: signed, first-seen → 0
        when(mockClient.getVersionHistory(GROUP, "dep-x")).thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(DEP_X)).thenReturn(signed(DEP_X, 5));

        // dep-y: signed both sides, dep count 5 → 16 (>3x) → DEP-EXPLOSION only → 25
        when(mockClient.getVersionHistory(GROUP, "dep-y")).thenReturn(List.of("1.0.0", "0.9.0"));
        when(mockClient.fetchMetadata(DEP_Y)).thenReturn(signed(DEP_Y, 16));
        when(mockClient.fetchMetadata(DEP_Y_PREV)).thenReturn(signed(DEP_Y_PREV, 5));

        // dep-z: unsigned, first-seen → MISSING-SIG only → 15
        when(mockClient.getVersionHistory(GROUP, "dep-z")).thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(DEP_Z)).thenReturn(unsigned(DEP_Z, 5));
    }

    private Map<String, Integer> scoresByPackage() throws IOException {
        JsonNode root = MAPPER.readTree(outBytes.toString());
        Map<String, Integer> scores = new HashMap<>();
        for (JsonNode f : root.get("findings")) {
            scores.put(f.get("package").asText(), f.get("risk_score").asInt());
        }
        return scores;
    }

    /** The single assertion both the Maven and Gradle runs must satisfy. */
    private void assertFullGraphScores(Map<String, Integer> scores) {
        assertThat(scores).containsEntry(GROUP + ":dep-a", 0);
        assertThat(scores).containsEntry(GROUP + ":dep-b", 0);
        assertThat(scores).containsEntry(GROUP + ":dep-x", 0);
        assertThat(scores).containsEntry(GROUP + ":dep-y", 25);
        assertThat(scores).containsEntry(GROUP + ":dep-z", 15);
        // Exactly the flattened, de-duplicated graph (dep-x resolved once):
        assertThat(scores).hasSize(5);
    }

    // ── the two real-resolver runs ───────────────────────────────────────────────

    @Test
    void mavenResolver_transitive_scoresFullGraph(@TempDir Path tmp, @TempDir Path localRepo) throws Exception {
        seedClient();

        // Copy the fixture (pom + local repo) to a writable dir and point the POM's
        // repository at the copied repo via its absolute file: URL.
        Path project = copyFixture("graph-maven", tmp.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Path repo = project.resolve("repo");
        Files.writeString(pom, Files.readString(pom).replace("@REPO_URL@", repo.toUri().toString()));

        // Keep the resolver's Aether local repository off the developer's ~/.m2.
        System.setProperty("marshal.localRepo", localRepo.toString());
        try {
            ScanCommand cmd = new ScanCommand(mockClient,
                    new PomDependencyResolver(EnumSet.of(DependencyScope.COMPILE)));
            int exit = new CommandLine(cmd).execute("--pom", pom.toString(), "--output", "JSON");

            assertThat(exit).isEqualTo(0);   // highest is dep-y YELLOW (25) < RED threshold
            assertFullGraphScores(scoresByPackage());
        } finally {
            System.clearProperty("marshal.localRepo");
        }
    }

    @Test
    void gradleResolver_transitive_scoresFullGraph(@TempDir Path tmp) throws Exception {
        seedClient();

        // Copy the fixture (build + local repo) to a writable dir so the real Gradle
        // build does not pollute the source tree with .gradle/build output.
        Path project = copyFixture("graph-gradle", tmp.resolve("project"));

        ScanCommand cmd = new ScanCommand(mockClient,
                new GradleDependencyResolver(EnumSet.of(DependencyScope.COMPILE), true));
        int exit = new CommandLine(cmd).execute(
                "--build-file", project.resolve("build.gradle.kts").toString(),
                "--output", "JSON");

        assertThat(exit).isEqualTo(0);
        assertFullGraphScores(scoresByPackage());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static Path fixture(String rel) {
        try {
            return Paths.get(Objects.requireNonNull(
                    GraphResolverScoringIT.class.getClassLoader().getResource("fixtures/" + rel)).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Fixture not found: " + rel, e);
        }
    }

    private static Path copyFixture(String id, Path dest) {
        Path src = fixture(id);
        try (Stream<Path> tree = Files.walk(src)) {
            tree.forEach(p -> {
                Path target = dest.resolve(src.relativize(p).toString());
                try {
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dest;
    }
}
