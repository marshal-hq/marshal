package dev.marshalhq.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.DependencyPathNode;

/**
 * The introduced-by parity guard: the {@code parity-paths} fixture declares the SAME
 * two direct dependencies as a Maven POM and a Gradle build, and both resolvers must
 * produce identical flattened graphs AND identical {@code dependencyPaths()} —
 * including the diamond (jackson-core reachable through both directs). If Gradle
 * blanks where Maven reports, the S17 parity gap is re-opened and this fails.
 *
 * Run with: ./gradlew :marshal-resolvers:integrationTest (nightly CI; real network +
 * a real Gradle build).
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class IntroducedByParityIT {

    private static final String DATABIND = "com.fasterxml.jackson.core:jackson-databind";
    private static final String YAML = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml";
    private static final String CORE = "com.fasterxml.jackson.core:jackson-core";

    @Test
    void mavenAndGradle_produceIdenticalIntroducedByPaths(@TempDir Path tmp) throws Exception {
        PomDependencyResolver maven = new PomDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME));
        List<Coordinates> mavenDeps = maven.resolve(fixture("parity-paths/pom.xml"));

        Path project = GradleFixtures.copyFixture("parity-paths", tmp.resolve("project"));
        // The repo's own wrapper script resolves its jar relative to itself, so it can
        // drive the fixture project without being copied into it.
        Path repoWrapper = findRepoRoot().resolve("gradlew").toAbsolutePath();
        GradleDependencyResolver gradle = new GradleDependencyResolver(
                EnumSet.of(DependencyScope.COMPILE, DependencyScope.RUNTIME), true,
                Duration.ofMinutes(4), scanRoot -> repoWrapper.toString());
        List<Coordinates> gradleDeps = gradle.resolve(project);

        // Same flattened graph on both build tools (S17) — modulo ONE known, pre-existing
        // divergence: Gradle materializes an imported BOM (jackson-bom) as a platform
        // component in its resolution result, Maven never does. That gap predates this
        // feature and is asserted exactly so nothing else can hide behind it.
        List<Coordinates> gradleExtras = gradleDeps.stream()
                .filter(c -> !mavenDeps.contains(c)).toList();
        assertThat(gradleExtras)
                .containsExactly(new Coordinates("com.fasterxml.jackson", "jackson-bom", "2.12.7"));
        assertThat(mavenDeps).allSatisfy(c -> assertThat(gradleDeps).contains(c));

        // …and identical introduced-by paths for every node both sides know (this
        // feature's parity guard). The BOM key is Gradle-only by the divergence above.
        Map<String, List<List<DependencyPathNode>>> mavenPaths = maven.dependencyPaths();
        Map<String, List<List<DependencyPathNode>>> gradlePaths = gradle.dependencyPaths();
        Map<String, List<List<DependencyPathNode>>> gradleSharedPaths = new java.util.TreeMap<>(gradlePaths);
        gradleSharedPaths.keySet().removeIf(ga -> ga.equals("com.fasterxml.jackson:jackson-bom"));
        assertThat(gradleSharedPaths).isEqualTo(mavenPaths);

        // Spot-check the known shape so a both-sides-empty regression cannot pass:
        // each direct is its own single one-element path…
        assertThat(mavenPaths.get(DATABIND)).hasSize(1);
        assertThat(mavenPaths.get(DATABIND).get(0)).hasSize(1);
        assertThat(mavenPaths.get(DATABIND).get(0).get(0).direct()).isTrue();

        // …and the diamond transitive carries BOTH paths, shortest-first with the
        // deterministic lexicographic tie-break (databind before dataformat-yaml).
        List<List<DependencyPathNode>> corePaths = mavenPaths.get(CORE);
        assertThat(corePaths).hasSize(2);
        assertThat(corePaths.get(0).get(0).toGa()).isEqualTo(DATABIND);
        assertThat(corePaths.get(1).get(0).toGa()).isEqualTo(YAML);
        for (List<DependencyPathNode> path : corePaths) {
            assertThat(path).hasSize(2);
            assertThat(path.get(0).direct()).isTrue();
            assertThat(path.get(1).direct()).isFalse();
            assertThat(path.get(1).artifactId()).isEqualTo("jackson-core");
        }
    }

    private static Path fixture(String name) {
        try {
            return Paths.get(Objects.requireNonNull(
                    IntroducedByParityIT.class.getClassLoader().getResource("fixtures/" + name)).toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException("Fixture not found: " + name, e);
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
