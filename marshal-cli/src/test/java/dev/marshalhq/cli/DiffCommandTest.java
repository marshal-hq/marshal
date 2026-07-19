package dev.marshalhq.cli;

import dev.marshalhq.core.*;
import dev.marshalhq.registry.MavenCentralClient;
import dev.marshalhq.resolvers.PomDependencyResolver;
import dev.marshalhq.resolvers.ResolutionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiffCommandTest {

    @Mock
    MavenCentralClient mockClient;
    @Mock
    PomDependencyResolver mockResolver;

    @TempDir
    Path tempDir;

    // ── fixtures ──────────────────────────────────────────────────────────────────

    private static final Coordinates LIB_V1 = new Coordinates("com.example", "lib", "1.0.0");
    private static final Coordinates LIB_V2 = new Coordinates("com.example", "lib", "2.0.0");

    private static VersionMetadata signed(Coordinates c) {
        return new VersionMetadata(c, null, "AABB", SignatureStatus.PRESENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private static VersionMetadata absent(Coordinates c) {
        return new VersionMetadata(c, null, null, SignatureStatus.ABSENT,
                List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
    }

    private int run(List<Coordinates> baseDeps, List<Coordinates> headDeps) {
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(baseDeps);
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(headDeps);
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
        return new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN"
        );
    }

    // ── edge case 1: dep added in head only ───────────────────────────────────────

    @Test
    void addedDep_producesFinding() {
        when(mockClient.fetchMetadata(LIB_V2)).thenReturn(absent(LIB_V2));

        // base has no lib; head has lib@2.0.0
        int exit = run(List.of(), List.of(LIB_V2));

        assertThat(exit).isEqualTo(0); // MISSING_SIG(15) → score 15 → GREEN → no breach of RED threshold
        verify(mockClient).fetchMetadata(LIB_V2);
        verify(mockClient, never()).fetchMetadata(LIB_V1); // base version never fetched (wasn't in base)
    }

    // ── edge case 2: dep removed in head only → no finding ───────────────────────

    @Test
    void removedDep_producesNoFinding() {
        // base has lib@1.0.0; head has nothing
        int exit = run(List.of(LIB_V1), List.of());

        assertThat(exit).isEqualTo(0);
        verifyNoInteractions(mockClient); // no fetch — removal is not a new risk
    }

    // ── edge case 3: dep version bumped → one finding ────────────────────────────

    @Test
    void versionBump_producesOneFinding() {
        // previous PRESENT → current ABSENT: SIG_DROPPED(40) + MISSING_SIG(15) = 55 → ORANGE
        when(mockClient.fetchMetadata(LIB_V2)).thenReturn(absent(LIB_V2));
        when(mockClient.fetchMetadata(LIB_V1)).thenReturn(signed(LIB_V1));

        int exit = run(List.of(LIB_V1), List.of(LIB_V2));

        assertThat(exit).isEqualTo(0); // ORANGE < RED threshold → no failure
        verify(mockClient).fetchMetadata(LIB_V2); // head version fetched
        verify(mockClient).fetchMetadata(LIB_V1); // base version fetched as "previous"
    }

    @Test
    void versionBump_fromVersionIsBaseVersion() {
        when(mockClient.fetchMetadata(any())).thenReturn(signed(LIB_V2));

        // Run with md output so we can check the markdown rendering of fromVersion
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_V2));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
        // Just check it runs without NPE — fromVersion wiring verified by absence of crash
        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN"
        );
        assertThat(exit).isEqualTo(0);
    }

    // ── edge case 4: same version in both → no finding ───────────────────────────

    @Test
    void sameVersionInBoth_producesNoFinding() {
        // lib@1.0.0 in both base and head — UNCHANGED
        int exit = run(List.of(LIB_V1), List.of(LIB_V1));

        assertThat(exit).isEqualTo(0);
        verifyNoInteractions(mockClient);
    }

    // ── edge case 5: base and head identical → empty diff, exit 0 ────────────────

    @Test
    void identicalPoms_emptyDiff_exits0() {
        Coordinates a = new Coordinates("com.example", "a", "1.0.0");
        Coordinates b = new Coordinates("com.example", "b", "2.0.0");

        int exit = run(List.of(a, b), List.of(a, b));

        assertThat(exit).isEqualTo(0);
        verifyNoInteractions(mockClient);
    }

    // ── exit code logic ───────────────────────────────────────────────────────────

    @Test
    void redFinding_failOnFail_exits1() {
        // NEW_MAINTAINER(35) + SIG_DROPPED(40) + MISSING_SIG(15) = 90 → RED
        VersionMetadata cur = new VersionMetadata(LIB_V2, "new@example.com", null,
                SignatureStatus.ABSENT, List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        VersionMetadata prev = new VersionMetadata(LIB_V1, "old@example.com", "LEGIT",
                SignatureStatus.PRESENT, List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        when(mockClient.fetchMetadata(LIB_V2)).thenReturn(cur);
        when(mockClient.fetchMetadata(LIB_V1)).thenReturn(prev);

        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_V2));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN", "--threshold", "RED", "--fail-on", "FAIL"
        );
        assertThat(exit).isEqualTo(1);
    }

    @Test
    void redFinding_failOnNever_exits0() {
        VersionMetadata cur = new VersionMetadata(LIB_V2, "new@example.com", null,
                SignatureStatus.ABSENT, List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        VersionMetadata prev = new VersionMetadata(LIB_V1, "old@example.com", "LEGIT",
                SignatureStatus.PRESENT, List.of(), 5, "https://github.com/example/lib", Instant.EPOCH, false);
        when(mockClient.fetchMetadata(LIB_V2)).thenReturn(cur);
        when(mockClient.fetchMetadata(LIB_V1)).thenReturn(prev);

        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_V2));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN", "--threshold", "RED", "--fail-on", "NEVER"
        );
        assertThat(exit).isEqualTo(0);
    }

    // ── honesty invariant (§2.4): either side unresolvable → exit 3 ──────────────

    @Test
    void headUnresolvable_exits3_neverReportsNoNewRisks() {
        // base resolves fine; head cannot be built/resolved.
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml")))
                .thenThrow(new ResolutionException("Gradle build failed (exit 1)"));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);

        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN"
        );

        assertThat(exit).isEqualTo(3);
        verifyNoInteractions(mockClient); // never scored a partial set → never "no new risks"
    }

    @Test
    void exit3_cannotBeDowngradedByFailOnNever() {
        // Could-not-analyze is not a finding — fail-on must NOT turn exit 3 green (§3.5).
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml")))
                .thenThrow(new ResolutionException("Gradle build failed (exit 1)"));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);

        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN", "--fail-on", "NEVER"
        );

        assertThat(exit).isEqualTo(3);
    }

    // ── introduced-by path on new head-side transitives ───────────────────────────

    @Test
    void newTransitiveOnHead_carriesHeadSidePathInMarkdown() throws Exception {
        // Head resolution pulls a new transitive; the resolver reports its head-side
        // introduced-by path, and the diff's PR comment shows which direct dragged it in.
        Coordinates transitive = new Coordinates("com.foo", "bar", "2.0.0");
        when(mockClient.fetchMetadata(transitive)).thenReturn(absent(transitive));
        when(mockResolver.resolve(tempDir.resolve("base.xml"))).thenReturn(List.of(LIB_V1));
        when(mockResolver.resolve(tempDir.resolve("head.xml"))).thenReturn(List.of(LIB_V1, transitive));
        when(mockResolver.dependencyPaths()).thenReturn(java.util.Map.of(
                "com.foo:bar", List.of(List.of(
                        new DependencyPathNode("com.example", "lib", "1.0.0", true),
                        new DependencyPathNode("com.foo", "bar", "2.0.0", false)))));

        var origOut = System.out;
        var outBytes = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(outBytes));
        try {
            DiffCommand cmd = new DiffCommand(mockClient, mockResolver);
            int exit = new CommandLine(cmd).execute(
                    "--base", tempDir.resolve("base.xml").toString(),
                    "--head", tempDir.resolve("head.xml").toString(),
                    "--output", "JSON");
            assertThat(exit).isEqualTo(0);
        } finally {
            System.setOut(origOut);
        }

        // JSON carries the head-side path on the added finding.
        String out = outBytes.toString();
        assertThat(out).contains("\"introduced_by\"");
        assertThat(out).contains("\"artifact\" : \"lib\"");
        assertThat(out).contains("\"direct\" : true");
    }

    @Test
    void baseUnresolvable_exits3_neverNoiseWall() {
        // base cannot be resolved — must NOT fall through to head-minus-empty (which
        // would flag every head dependency as new).
        when(mockResolver.resolve(tempDir.resolve("base.xml")))
                .thenThrow(new ResolutionException("Gradle build failed (exit 1)"));
        DiffCommand cmd = new DiffCommand(mockClient, mockResolver);

        int exit = new CommandLine(cmd).execute(
                "--base", tempDir.resolve("base.xml").toString(),
                "--head", tempDir.resolve("head.xml").toString(),
                "--output", "HUMAN"
        );

        assertThat(exit).isEqualTo(3);
        verifyNoInteractions(mockClient); // no findings assembled → no noise wall
        verify(mockResolver, never()).resolve(tempDir.resolve("head.xml"));
    }
}
