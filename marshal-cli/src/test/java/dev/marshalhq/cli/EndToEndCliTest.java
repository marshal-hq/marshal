package dev.marshalhq.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import picocli.CommandLine;

import java.io.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end CLI tests: exercise the full scan/diff command stack as the user
 * would, capturing real System.out and System.err, asserting on observable output.
 * Mocks MavenCentralClient and PomDependencyResolver only — no real network,
 * no real file I/O beyond temp paths.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EndToEndCliTest {

    @Mock
    MavenCentralClient mockClient;
    @Mock
    PomDependencyResolver mockResolver;
    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── System.out / System.err capture ────────────────────────────────────────

    private PrintStream origOut, origErr;
    private ByteArrayOutputStream outBytes, errBytes;

    @BeforeEach
    void redirectStreams() {
        origOut = System.out;
        origErr = System.err;
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes));
        System.setErr(new PrintStream(errBytes));
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(origOut);
        System.setErr(origErr);
    }

    private String stdout() {
        return outBytes.toString();
    }

    private String stderr() {
        return errBytes.toString();
    }

    // ── shared fixtures ────────────────────────────────────────────────────────

    private static final Coordinates LIB_A_200 = new Coordinates("com.example", "lib-a", "2.0.0");
    private static final Coordinates LIB_A_100 = new Coordinates("com.example", "lib-a", "1.0.0");
    private static final Coordinates LIB_A_101 = new Coordinates("com.example", "lib-a", "1.0.1");
    private static final Coordinates LIB_B_100 = new Coordinates("com.example", "lib-b", "1.0.0");
    private static final Coordinates LIB_B_200 = new Coordinates("com.example", "lib-b", "2.0.0");

    private static VersionMetadata presentMeta(Coordinates c, String key) {
        return new VersionMetadata(c, null, key, SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata absentMeta(Coordinates c) {
        return new VersionMetadata(c, null, null, SignatureStatus.ABSENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    /**
     * RED fixture: email change + sig dropped. NEW_MAINTAINER(35) + SIG_DROPPED(40) + MISSING_SIG(15) = 90 → RED
     */
    private static VersionMetadata redCurrent(Coordinates c) {
        return new VersionMetadata(c, "new@example.com", null, SignatureStatus.ABSENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata redPrevious(Coordinates c) {
        return new VersionMetadata(c, "old@example.com", "LEGITKEY", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private int runScan(String... extraArgs) {
        ScanCommand cmd = new ScanCommand(mockClient, mockResolver);
        String[] baseArgs = { "--pom", tempDir.resolve("pom.xml").toString() };
        String[] allArgs = new String[baseArgs.length + extraArgs.length];
        System.arraycopy(baseArgs, 0, allArgs, 0, baseArgs.length);
        System.arraycopy(extraArgs, 0, allArgs, baseArgs.length, extraArgs.length);
        return new CommandLine(cmd).execute(allArgs);
    }

    private int runDiff(String... extraArgs) {
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
        String[] baseArgs = {
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString()
        };
        String[] allArgs = new String[baseArgs.length + extraArgs.length];
        System.arraycopy(baseArgs, 0, allArgs, 0, baseArgs.length);
        System.arraycopy(extraArgs, 0, allArgs, baseArgs.length, extraArgs.length);
        return new CommandLine(cmd).execute(allArgs);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B3.1 — Human output is clean
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void b31_humanOutput_isClean() {
        // lib-a: PRESENT, prev PRESENT same key → GREEN
        when(mockClient.getVersionHistory("com.example", "lib-a")).thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A_200)).thenReturn(presentMeta(LIB_A_200, "AABBCC"));
        when(mockClient.fetchMetadata(LIB_A_100)).thenReturn(presentMeta(LIB_A_100, "AABBCC"));
        // lib-b: PRESENT, first-seen → GREEN
        when(mockClient.getVersionHistory("com.example", "lib-b")).thenReturn(List.of("1.0.0"));
        when(mockClient.fetchMetadata(LIB_B_100)).thenReturn(presentMeta(LIB_B_100, "AABBCC"));

        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A_200, LIB_B_100));

        int exit = runScan("--output", "HUMAN");

        assertThat(exit).isEqualTo(0);
        assertThat(stdout()).contains("WATCH").contains("ANALYZE").contains("BLOCK");
        assertThat(stdout()).contains("2 dependencies").contains("0 flagged");
        assertThat(stdout()).contains("─");       // at least one box-drawing divider
        assertThat(stdout()).doesNotContain("[WARN]");
        assertThat(stdout()).doesNotContain("Exception");
        // stderr is empty (no warnings triggered)
        assertThat(stderr().strip()).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B3.2 — Markdown output is pure markdown, [WARN] goes to stderr not stdout
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void b32_markdownOutput_isPure_warnGoesToStderr() {
        when(mockClient.getVersionHistory("com.example", "lib-a")).thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A_200)).thenReturn(redCurrent(LIB_A_200));
        when(mockClient.fetchMetadata(LIB_A_100)).thenReturn(redPrevious(LIB_A_100));
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A_200));

        int exit = runScan("--output", "MD", "--threshold", "RED", "--fail-on", "WARN");

        assertThat(exit).isEqualTo(0);  // fail-on=warn → always 0

        String out = stdout();
        // First line must be the idempotency marker
        assertThat(out.lines().findFirst().orElse("")).isEqualTo("<!-- marshal-bot -->");
        assertThat(out).contains("## 🛡 Marshal Dependency Analysis");
        assertThat(out).contains("### 🔴 HIGH RISK");
        assertThat(out).contains("<sub>Powered by [Marshal]");
        // No warning line on stdout
        assertThat(out).doesNotContain("[WARN]");
        assertThat(out).doesNotContain("Exception");
        // No ANSI escape sequences
        assertThat(out).doesNotContain("[");
        // [WARN] goes to stderr
        assertThat(stderr()).contains("[WARN] marshal:");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B3.3 — JSON output is pure JSON, schema-locked, handles UNRESOLVED
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void b33_jsonOutput_isPureJson_schemaLocked() throws Exception {
        Coordinates libC = new Coordinates("com.example", "lib-c", "UNRESOLVED");

        when(mockClient.getVersionHistory("com.example", "lib-a")).thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A_200)).thenReturn(redCurrent(LIB_A_200));
        when(mockClient.fetchMetadata(LIB_A_100)).thenReturn(redPrevious(LIB_A_100));
        when(mockClient.getVersionHistory("com.example", "lib-b")).thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_B_200)).thenReturn(presentMeta(LIB_B_200, "AABBCC"));
        when(mockClient.fetchMetadata(LIB_B_100)).thenReturn(presentMeta(LIB_B_100, "AABBCC"));
        // lib-c is UNRESOLVED — no registry calls should be made for it
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A_200, LIB_B_200, libC));

        int exit = runScan("--output", "JSON");  // default --fail-on fail, --threshold red

        assertThat(exit).isEqualTo(1);  // RED finding → fail

        String out = stdout();
        assertThat(out).doesNotContain("[");  // no ANSI
        assertThat(stderr()).doesNotContain("[WARN]");  // fail→no warn

        JsonNode root = MAPPER.readTree(out);  // throws if not valid JSON
        assertThat(root.get("schema").asText()).isEqualTo("1.0");
        assertThat(root.get("summary").get("total_dependencies").asInt()).isEqualTo(3);
        assertThat(root.get("summary").get("flagged").asInt()).isEqualTo(1);  // RED only

        JsonNode dist = root.get("summary").get("risk_distribution");
        assertThat(dist.has("red")).isTrue();
        assertThat(dist.has("orange")).isTrue();
        assertThat(dist.has("yellow")).isTrue();
        assertThat(dist.has("green")).isTrue();
        // UNRESOLVED not counted in distribution
        assertThat(dist.get("red").asInt() + dist.get("orange").asInt()
                + dist.get("yellow").asInt() + dist.get("green").asInt()).isEqualTo(2);

        // Unresolved finding present in array with marker
        boolean foundUnresolved = false;
        for (JsonNode f : root.get("findings")) {
            if ("com.example:lib-c".equals(f.get("package").asText())) {
                assertThat(f.get("risk_level").asText()).isEqualTo("unresolved");
                foundUnresolved = true;
            }
        }
        assertThat(foundUnresolved).as("unresolved finding must appear in findings[]").isTrue();
        // Registry was never called for the UNRESOLVED dep
        verify(mockClient, never()).fetchMetadata(libC);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B3.4 — Diff version bump produces exactly one finding, unchanged skipped
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void b34_diffVersionBump_producesExactlyOneFinding() throws Exception {
        // base: lib-a 1.0.0, lib-b 2.0.0
        // head: lib-a 1.0.1 (bumped), lib-b 2.0.0 (unchanged)
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_A_100, LIB_B_200));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_A_101, LIB_B_200));

        // lib-a 1.0.1: ABSENT, prev 1.0.0: PRESENT → SIG_DROPPED(40) + MISSING_SIG(15) = 55 → ORANGE
        when(mockClient.fetchMetadata(LIB_A_101)).thenReturn(absentMeta(LIB_A_101));
        when(mockClient.fetchMetadata(LIB_A_100)).thenReturn(presentMeta(LIB_A_100, "AABBCC"));

        int exit = runDiff("--output", "JSON", "--threshold", "RED");

        assertThat(exit).isEqualTo(0);  // ORANGE < RED threshold

        JsonNode root = MAPPER.readTree(stdout());
        JsonNode findings = root.get("findings");
        assertThat(findings.size()).isEqualTo(1);

        JsonNode f = findings.get(0);
        assertThat(f.get("package").asText()).isEqualTo("com.example:lib-a");
        assertThat(f.get("from_version").asText()).isEqualTo("1.0.0");
        assertThat(f.get("to_version").asText()).isEqualTo("1.0.1");
        assertThat(f.get("risk_level").asText()).isEqualTo("orange");

        // lib-b was unchanged — never fetched
        verify(mockClient).fetchMetadata(LIB_A_101);
        verify(mockClient).fetchMetadata(LIB_A_100);
        verify(mockClient, never()).fetchMetadata(LIB_B_200);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // B3.5 — Diff markdown carries the idempotency marker as the literal first line
    // ─────────────────────────────────────────────────────────────────────────────

    @Test
    void b35_diffMarkdown_hasIdempotencyMarkerAsFirstLine() {
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_A_100));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_A_101));
        when(mockClient.fetchMetadata(LIB_A_101)).thenReturn(absentMeta(LIB_A_101));
        when(mockClient.fetchMetadata(LIB_A_100)).thenReturn(presentMeta(LIB_A_100, "AABBCC"));

        int exit = runDiff("--output", "MD");

        assertThat(exit).isEqualTo(0);

        String out = stdout();
        List<String> lines = out.lines().toList();

        // First line must be the exact marker — no whitespace, no BOM, nothing before it
        assertThat(lines.get(0)).isEqualTo("<!-- marshal-bot -->");

        // Marker appears exactly once
        long markerCount = lines.stream().filter("<!-- marshal-bot -->"::equals).count();
        assertThat(markerCount).isEqualTo(1);

        // Next non-empty line after the marker starts with "## 🛡 Marshal"
        String nextNonEmpty = lines.stream().skip(1).filter(l -> !l.isBlank()).findFirst().orElse("");
        assertThat(nextNonEmpty).startsWith("## 🛡 Marshal");
    }
}
