package dev.marshalhq.resolvers;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.condition.OS.WINDOWS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.io.TempDir;

import dev.marshalhq.core.Coordinates;

/**
 * Fast unit tests for {@link GradleDependencyResolver} that do not spawn a real
 * Gradle build. End-to-end resolution against a real wrapper lives in
 * GradleDependencyResolverIntegrationTest.
 */
class GradleDependencyResolverTest {

    @Test
    @DisabledOnOs(WINDOWS)
    void failedGradleBuildThrowsNeverReportsClean(@TempDir Path dir) throws Exception {
        // A wrapper that always fails — Marshal must signal "could not analyze", never clean (S06).
        writeFailingWrapper(dir);

        GradleDependencyResolver resolver = new GradleDependencyResolver();
        Path buildFile = dir.resolve("build.gradle.kts");

        assertThatThrownBy(() -> resolver.resolve(buildFile))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("build failed");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void wrapperThatWritesNoOutputThrows(@TempDir Path dir) throws Exception {
        // exits 0 but writes NOTHING to marshalOut. The init script always
        // writes JSON (even "[]") when it runs, so a missing file means the task never
        // ran → could-not-analyze (exit 3), never a clean empty result (S06).
        writeWrapper(dir, "#!/bin/sh\nexit 0\n");

        assertThatThrownBy(() ->
                new GradleDependencyResolver().resolve(dir.resolve("build.gradle.kts")))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("no dependency output");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void genuineEmptyGraphReturnsEmptyList(@TempDir Path dir) throws Exception {
        // exits 0 and writes a valid empty array — a real zero-dependency project.
        // This is the legitimate empty case and must NOT throw (contrast with the no-output case above).
        writeWrapper(dir, marshalOutWriter("[]"));

        List<Coordinates> deps =
                new GradleDependencyResolver().resolve(dir.resolve("build.gradle.kts"));

        assertThat(deps).isEmpty();
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void malformedJsonThrows(@TempDir Path dir) throws Exception {
        // exits 0 but the output is not valid JSON → could-not-analyze, not clean.
        writeWrapper(dir, marshalOutWriter("{ this is not json"));

        assertThatThrownBy(() ->
                new GradleDependencyResolver().resolve(dir.resolve("build.gradle.kts")))
                .isInstanceOf(ResolutionException.class);
    }

    @Test
    @DisabledOnOs(WINDOWS)
    @Timeout(30)
    void wrapperThatHangsTimesOut(@TempDir Path dir) throws Exception {
        // a wrapper that never returns → the resolver's timeout fires, the process
        // is killed, and we get could-not-analyze rather than a hang or a false-clean.
        writeWrapper(dir, "#!/bin/sh\nsleep 60\n");
        GradleDependencyResolver resolver = new GradleDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE), false, Duration.ofMillis(500));

        assertThatThrownBy(() -> resolver.resolve(dir.resolve("build.gradle.kts")))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    @DisabledOnOs(WINDOWS)
    void parsesAndDedupesWrapperJsonOutput(@TempDir Path dir) throws Exception {
        // Fake wrapper that echoes a known graph (with a duplicate GAV across configs)
        // into the -PmarshalOut path, proving the parse + dedupe path independently of Gradle.
        String json = "["
                + "{\"group\":\"com.google.guava\",\"name\":\"guava\",\"version\":\"33.0.0-jre\","
                + "\"configuration\":\"compileClasspath\",\"project\":\":\",\"direct\":true},"
                + "{\"group\":\"com.google.guava\",\"name\":\"guava\",\"version\":\"33.0.0-jre\","
                + "\"configuration\":\"runtimeClasspath\",\"project\":\":\",\"direct\":true},"
                + "{\"group\":\"org.slf4j\",\"name\":\"slf4j-api\",\"version\":\"2.0.12\","
                + "\"configuration\":\"runtimeClasspath\",\"project\":\":\",\"direct\":false}"
                + "]";
        // The wrapper extracts the marshalOut path from its args and writes the fixture there.
        writeWrapper(dir, "#!/bin/sh\n"
                + "for a in \"$@\"; do case \"$a\" in -PmarshalOut=*) out=\"${a#-PmarshalOut=}\";; esac; done\n"
                + "cat > \"$out\" <<'JSON'\n" + json + "\nJSON\n");

        List<Coordinates> deps = new GradleDependencyResolver().resolve(dir.resolve("build.gradle.kts"));

        assertThat(deps).containsExactlyInAnyOrder(
                new Coordinates("com.google.guava", "guava", "33.0.0-jre"),
                new Coordinates("org.slf4j", "slf4j-api", "2.0.12"));
    }

    private static void writeFailingWrapper(Path dir) throws Exception {
        writeWrapper(dir, "#!/bin/sh\necho 'boom' >&2\nexit 1\n");
    }

    /** A wrapper script that writes the given literal payload to the -PmarshalOut path. */
    private static String marshalOutWriter(String payload) {
        return "#!/bin/sh\n"
                + "for a in \"$@\"; do case \"$a\" in -PmarshalOut=*) out=\"${a#-PmarshalOut=}\";; esac; done\n"
                + "cat > \"$out\" <<'PAYLOAD'\n" + payload + "\nPAYLOAD\n";
    }

    private static void writeWrapper(Path dir, String script) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "");
        Path wrapper = dir.resolve("gradlew");
        Files.writeString(wrapper, script);
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
