package dev.marshalhq.resolvers;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.marshalhq.core.Coordinates;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link GradleDependencyResolver}. Spawns a real Gradle build
 * via the project's own wrapper against the marshal repo itself (a multi-module
 * Gradle project), exercising the bundled init script end to end.
 *
 * Run with: ./gradlew :marshal-resolvers:integrationTest
 * Not part of the per-push suite — it executes a full Gradle resolution.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class GradleDependencyResolverIntegrationTest {

    @Test
    void resolvesMarshalRepoTransitively() {
        Path repoRoot = findRepoRoot();
        List<Coordinates> deps = new GradleDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME)).resolve(repoRoot);

        // Direct declarations of the marshal modules show up...
        assertThat(deps).contains(new Coordinates("info.picocli", "picocli", "4.7.5"));
        assertThat(deps).anyMatch(c -> c.groupId().equals("com.fasterxml.jackson.core")
                && c.artifactId().equals("jackson-databind"));

        // ...and so do transitives the marshal build never declares directly (S13 asymmetry).
        assertThat(deps).anyMatch(c -> c.artifactId().equals("jackson-core"));

        // Resolved versions only — no UNRESOLVED sentinels leak through the Gradle path.
        assertThat(deps).noneMatch(c -> c.version().equals("UNRESOLVED") || c.version().equals("STUB"));

        // Dedupe holds: each group:artifact:version appears once.
        assertThat(deps).doesNotHaveDuplicates();
    }

    /**
     * A real Gradle build whose script does not compile must surface as could-not-analyze
     * (exit 3 via {@link ResolutionException}), never a clean or empty result. Exercises
     * the full shell-out path: locate wrapper, run, fail.
     */
    @Test
    void brokenBuildScriptThrows_neverClean(@TempDir Path tmp) throws Exception {
        Path project = GradleFixtures.copyFixture("broken-build-script", tmp.resolve("project"));
        copyWrapper(findRepoRoot(), project);

        GradleDependencyResolver resolver = new GradleDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME), true);

        assertThatThrownBy(() -> resolver.resolve(project))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("could not resolve dependencies");
    }

    /**
     * A partial failure (one declared dependency exists nowhere) must not fail the whole
     * resolution: the resolver returns a populated list containing an UNRESOLVED
     * coordinate, parity with the Maven UNRESOLVED-version path. Never dropped
     * (false-clean), never exit 3.
     */
    @Test
    void unresolvableDependencyBecomesUnresolvedEntry_neverDropped(@TempDir Path tmp)
            throws Exception {
        Path project = GradleFixtures.copyFixture("unresolvable-dependency", tmp.resolve("project"));
        copyWrapper(findRepoRoot(), project);

        List<Coordinates> deps = new GradleDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME), true).resolve(project);

        assertThat(deps).contains(new Coordinates("com.google.guava", "guava", "33.0.0-jre"));
        assertThat(deps).anyMatch(c ->
                c.groupId().equals("com.example.marshal.nonexistent")
                        && c.version().equals("UNRESOLVED"));
    }

    /** Copies the repo's own Gradle wrapper into a fixture dir so it can run standalone. */
    private static void copyWrapper(Path repoRoot, Path dest) throws IOException {
        Path gradlew = dest.resolve("gradlew");
        Files.copy(repoRoot.resolve("gradlew"), gradlew);
        Files.setPosixFilePermissions(gradlew, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path wrapperDir = repoRoot.resolve("gradle/wrapper");
        try (Stream<Path> tree = Files.walk(wrapperDir)) {
            tree.forEach(p -> {
                Path target = dest.resolve(repoRoot.relativize(p).toString());
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
        }
    }

    private static Path findRepoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("marshal repo root with settings.gradle.kts").isNotNull();
        return dir;
    }
}
