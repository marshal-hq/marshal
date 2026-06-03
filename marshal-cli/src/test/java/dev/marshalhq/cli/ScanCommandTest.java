package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScanCommandTest {

    @Mock
    MavenCentralClient mockClient;
    @Mock
    PomDependencyResolver mockResolver;

    @TempDir
    Path tempDir;

    // --- fixtures ---

    private static final Coordinates LIB_A =
            new Coordinates("com.example", "lib-a", "2.0.0");
    private static final Coordinates LIB_A_PREV =
            new Coordinates("com.example", "lib-a", "1.0.0");

    private static VersionMetadata signedMeta(Coordinates c) {
        return new VersionMetadata(c, null, "AABBCCDD", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata unsignedMeta(Coordinates c) {
        return new VersionMetadata(c, null, null, SignatureStatus.ABSENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata unknownMeta(Coordinates c) {
        return new VersionMetadata(c, null, null, SignatureStatus.UNKNOWN,
                List.of(), -1, null, Instant.EPOCH, false);
    }

    // --- helpers ---

    private Path writePom(String content) throws IOException {
        Path p = tempDir.resolve("pom.xml");
        Files.writeString(p, content);
        return p;
    }

    private int run(ScanCommand cmd, String... args) {
        return new CommandLine(cmd).execute(args);
    }

    // -------------------------------------------------------------------------
    // Pipeline produces a Finding for every dep
    // -------------------------------------------------------------------------

    @Test
    void pipelineProducesFindingForEachResolvedDep() throws Exception {
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(signedMeta(LIB_A));
        when(mockClient.fetchMetadata(LIB_A_PREV)).thenReturn(signedMeta(LIB_A_PREV));

        ScanCommand cmd = new ScanCommand(mockClient, mockResolver);
        int exit = run(cmd, "--pom", tempDir.resolve("pom.xml").toString());

        // Signed current + signed previous → GREEN → no failure
        assertThat(exit).isEqualTo(0);
        verify(mockClient).fetchMetadata(LIB_A);
        verify(mockClient).fetchMetadata(LIB_A_PREV);
    }

    @Test
    void firstSeenDep_noPreviousVersion_doesNotNPE() throws Exception {
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0")); // only one version — no previous
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(unsignedMeta(LIB_A));

        ScanCommand cmd = new ScanCommand(mockClient, mockResolver);
        // Must not throw; MISSING_SIG fires (15 pts → YELLOW)
        int exit = run(cmd, "--pom", tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(0); // YELLOW < RED threshold default
    }

    @Test
    void unresolvedDep_appearsInOutput_notEvaluated() throws Exception {
        Coordinates unres = new Coordinates("com.example", "lib-b", "UNRESOLVED");
        when(mockResolver.resolve(any())).thenReturn(List.of(unres));

        ScanCommand cmd = new ScanCommand(mockClient, mockResolver);
        int exit = run(cmd, "--pom", tempDir.resolve("pom.xml").toString());

        assertThat(exit).isEqualTo(0);
        verifyNoInteractions(mockClient); // no fetch for UNRESOLVED deps
    }

    @Test
    void unknownSignatureStatus_doesNotProduceFalseYellow() throws Exception {
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0"));
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(unknownMeta(LIB_A));

        ScanCommand cmd = new ScanCommand(mockClient, mockResolver);
        // UNKNOWN → MissingSignatureRule abstains → score 0 → GREEN → exit 0
        int exit = run(cmd, "--pom", tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Exit code logic
    // -------------------------------------------------------------------------

    private void setupRedFinding() {
        // current ABSENT + previous PRESENT → SIGNATURE_DROPPED (40) + MISSING_SIG (15) = 55 → ORANGE
        // To get RED: use high score — mock a maintainer change too
        Coordinates prevA = new Coordinates("com.example", "lib-a", "1.0.0");
        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0", "1.0.0"));
        // current: different key from previous → NEW_MAINTAINER(35) + MISSING_SIG(15) + SIG_DROPPED(40) = 90 → RED
        VersionMetadata cur = new VersionMetadata(LIB_A, "new@example.com", null, SignatureStatus.ABSENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        VersionMetadata prev = new VersionMetadata(prevA, "old@example.com", "LEGIT", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(cur);
        when(mockClient.fetchMetadata(prevA)).thenReturn(prev);
    }

    @Test
    void failOnFail_redFinding_redThreshold_exits1() {
        setupRedFinding();
        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString(),
                "--threshold", "RED", "--fail-on", "FAIL");
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void failOnWarn_redFinding_redThreshold_exits0() {
        setupRedFinding();
        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString(),
                "--threshold", "RED", "--fail-on", "WARN");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void failOnNever_redFinding_redThreshold_exits0() {
        setupRedFinding();
        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString(),
                "--threshold", "RED", "--fail-on", "NEVER");
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void belowThreshold_doesNotFail() {
        // DEP_EXPLOSION (25 pts) → YELLOW; threshold=RED → no failure
        VersionMetadata cur = new VersionMetadata(LIB_A, null, "AABB", SignatureStatus.PRESENT,
                List.of(), 16, "https://github.com/example/lib", Instant.EPOCH, false);
        VersionMetadata prev = new VersionMetadata(LIB_A_PREV, null, "AABB", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);

        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(cur);
        when(mockClient.fetchMetadata(LIB_A_PREV)).thenReturn(prev);

        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString(),
                "--threshold", "RED", "--fail-on", "FAIL");
        assertThat(exit).isEqualTo(0); // YELLOW < RED threshold → no failure
    }

    @Test
    void thresholdYellow_yellowFinding_failOnFail_exits1() {
        // DEP_EXPLOSION fires (16 > 5*3=15) → score 25 → YELLOW (25 >= 21)
        VersionMetadata cur = new VersionMetadata(LIB_A, null, "AABB", SignatureStatus.PRESENT,
                List.of(), 16, "https://github.com/example/lib", Instant.EPOCH, false);
        VersionMetadata prev = new VersionMetadata(LIB_A_PREV, null, "AABB", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);

        when(mockResolver.resolve(any())).thenReturn(List.of(LIB_A));
        when(mockClient.getVersionHistory("com.example", "lib-a"))
                .thenReturn(List.of("2.0.0", "1.0.0"));
        when(mockClient.fetchMetadata(LIB_A)).thenReturn(cur);
        when(mockClient.fetchMetadata(LIB_A_PREV)).thenReturn(prev);

        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString(),
                "--threshold", "YELLOW", "--fail-on", "FAIL");
        assertThat(exit).isEqualTo(1); // YELLOW >= YELLOW threshold → fail
    }

    @Test
    void noDependencies_exits0() {
        when(mockResolver.resolve(any())).thenReturn(List.of());
        int exit = run(new ScanCommand(mockClient, mockResolver),
                "--pom", tempDir.resolve("pom.xml").toString());
        assertThat(exit).isEqualTo(0);
    }

    @Test
    void failOnWarn_mdOutput_warningGoesToStderr_notStdout() {
        setupRedFinding();

        java.io.PrintStream origOut = System.out;
        java.io.PrintStream origErr = System.err;
        java.io.ByteArrayOutputStream outBytes = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(outBytes));
        System.setErr(new java.io.PrintStream(errBytes));
        try {
            int exit = run(new ScanCommand(mockClient, mockResolver),
                    "--pom", tempDir.resolve("pom.xml").toString(),
                    "--output", "MD",
                    "--threshold", "RED",
                    "--fail-on", "WARN");
            assertThat(exit).isEqualTo(0);
            // Markdown on stdout must not contain the warning line
            assertThat(outBytes.toString()).doesNotContain("[WARN] marshal:");
            // Warning must appear on stderr
            assertThat(errBytes.toString()).contains("[WARN] marshal:");
        }
        finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
    }
}
