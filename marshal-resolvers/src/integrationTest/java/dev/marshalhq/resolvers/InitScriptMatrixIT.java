package dev.marshalhq.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Init-script correctness across a range of Gradle project shapes, driven by Gradle
 * TestKit. Each test copies a committed fixture, runs {@code marshalDeps} with the
 * bundled init script, and asserts the emitted coordinates.
 *
 * <p><b>Gradle version range.</b> The declared support floor is Gradle 7.0, but TestKit
 * launches the target Gradle on the same JVM the tests run on, and Gradle only runs on
 * JDK 21 from 8.5 onward. The nightly runner is JDK 21, so the version matrix here is
 * bounded below by 8.5. Exercising the 7.x floor needs a JDK 19 (or lower) runner, which
 * is tracked as follow-up. Override the list with {@code -DmarshalGradleVersions=8.5,8.8}.
 */
class InitScriptMatrixIT {

    private static final String GUAVA = "com.google.guava:guava:33.0.0-jre";
    private static final String COMMONS = "org.apache.commons:commons-lang3:3.14.0";

    /** JDK-21-runnable versions; the version-catalog floor is 7.4 but all of these clear it. */
    static List<String> gradleVersions() {
        String override = System.getProperty("marshalGradleVersions");
        if (override != null && !override.isBlank()) {
            return List.of(override.split(","));
        }
        return List.of("8.5", GradleVersion.current().getVersion());
    }

    // ── fixtures run across every supported Gradle version ───────────────────────────

    @ParameterizedTest(name = "single-module Groovy @ Gradle {0}")
    @MethodSource("gradleVersions")
    void singleModuleGroovy(String version, @TempDir Path tmp) {
        Set<String> gavs = run("single-module-groovy", version, tmp, false);
        assertThat(gavs).contains(GUAVA, COMMONS);
    }

    @ParameterizedTest(name = "multi-module dedup @ Gradle {0}")
    @MethodSource("gradleVersions")
    void multiModuleDedup(String version, @TempDir Path tmp) {
        Set<String> gavs = run("multi-module", version, tmp, false);
        // Deps from both subprojects appear; the shared GAV is deduped to exactly one;
        // the sibling project component (:core) is never emitted as a coordinate.
        assertThat(gavs).contains(GUAVA, COMMONS);
        assertThat(gavs).filteredOn(g -> g.equals(GUAVA)).hasSize(1);
        assertThat(gavs).noneMatch(g -> g.contains(":core:"));
    }

    @ParameterizedTest(name = "version catalog @ Gradle {0}")
    @MethodSource("gradleVersions")
    void versionCatalogResolvesConcrete(String version, @TempDir Path tmp) {
        // The init script reads the resolved graph, so the alias never appears, only the
        // catalog-pinned concrete GAV. (Parity win over Maven, which leaves it UNRESOLVED.)
        Set<String> gavs = run("version-catalog", version, tmp, false);
        assertThat(gavs).contains(GUAVA);
    }

    @ParameterizedTest(name = "BOM/platform @ Gradle {0}")
    @MethodSource("gradleVersions")
    void bomManagedVersionIsConcrete(String version, @TempDir Path tmp) {
        Set<String> gavs = run("bom-platform", version, tmp, false);
        // The versionless starter resolves to a concrete version, never blank/UNRESOLVED.
        assertThat(gavs).anyMatch(g ->
                g.startsWith("org.springframework.boot:spring-boot-starter:"));
        assertThat(gavs).noneMatch(g -> g.endsWith(":") || g.endsWith(":UNRESOLVED"));
    }

    // ── fixtures run on the current Gradle ───────────────────────────────────────────

    @Test
    void kotlinDslParity(@TempDir Path tmp) {
        Set<String> gavs = run("single-module-kotlin", current(), tmp, false);
        assertThat(gavs).contains(GUAVA, COMMONS);
    }

    @Test
    void settingsOnlyRoot(@TempDir Path tmp) {
        // No root build file: resolution must still find the submodule's deps.
        Set<String> gavs = run("settings-only-root", current(), tmp, false);
        assertThat(gavs).contains(GUAVA);
    }

    @Test
    void conventionPluginDeps(@TempDir Path tmp) {
        // The dependency is declared in a buildSrc convention plugin, not the leaf build.
        Set<String> gavs = run("convention-plugin", current(), tmp, false);
        assertThat(gavs).contains(GUAVA);
    }

    @Test
    void scopeMapping(@TempDir Path tmp) {
        Set<String> dflt = run("dependency-scopes", current(), tmp, false);
        // Default = compile + runtime classpaths: api, implementation, compileOnly, runtimeOnly.
        assertThat(dflt).contains(
                GUAVA,                                       // api
                COMMONS,                                     // implementation
                "com.google.code.findbugs:jsr305:3.0.2",     // compileOnly
                "org.slf4j:slf4j-simple:2.0.12");            // runtimeOnly
        assertThat(dflt).noneMatch(g -> g.startsWith("org.assertj:")); // test excluded
    }

    @Test
    void testScopeOptIn(@TempDir Path tmp) {
        Set<String> withTest = run("dependency-scopes", current(), tmp, true);
        assertThat(withTest).anyMatch(g -> g.startsWith("org.assertj:assertj-core:"));
    }

    @Test
    void testOnly_defaultEmpty_optInPopulated(@TempDir Path tmp, @TempDir Path tmp2) {
        assertThat(run("test-only", current(), tmp, false)).isEmpty();
        assertThat(run("test-only", current(), tmp2, true))
                .anyMatch(g -> g.startsWith("org.assertj:"));
    }

    @Test
    void compositeBuild_skipsSubstitutedProject(@TempDir Path tmp) {
        Set<String> gavs = run("composite-build", current(), tmp, false);
        assertThat(gavs).contains(GUAVA);                                 // genuine external
        assertThat(gavs).noneMatch(g -> g.contains("included-lib"));      // substituted project
    }

    @Test
    void dependencyLocking_resolvesNormally(@TempDir Path tmp) {
        Set<String> gavs = run("dependency-locking", current(), tmp, false);
        assertThat(gavs).contains("org.slf4j:slf4j-api:2.0.12");
    }

    @Test
    void unresolvableDep_emittedAsUnresolved_notDropped(@TempDir Path tmp) {
        // A valid build with one nonexistent dependency: resolutionResult is lenient, so
        // marshalDeps succeeds, but the missing dep must appear with the UNRESOLVED
        // sentinel (never silently dropped → false-clean). The resolvable dep is present.
        Set<String> gavs = run("unresolvable-dependency", current(), tmp, false);
        assertThat(gavs).contains(GUAVA);
        assertThat(gavs).contains(
                "com.example.marshal.nonexistent:does-not-exist:UNRESOLVED");
    }

    @Test
    void nonCentralDependency_emittedWithConcreteVersion(@TempDir Path tmp) {
        // The resolver emits a coordinate from a non-Central repo with a concrete version;
        // whether it exists on Maven Central is decided downstream by the registry client.
        Set<String> gavs = run("non-central-repo", current(), tmp, false);
        assertThat(gavs).anyMatch(g -> g.startsWith("org.springframework:spring-core:"));
        assertThat(gavs).noneMatch(g -> g.endsWith(":") || g.endsWith(":UNRESOLVED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private static String current() {
        return GradleVersion.current().getVersion();
    }

    private Set<String> run(String fixture, String gradleVersion, Path tmp, boolean includeTest) {
        Path project = GradleFixtures.copyFixture(fixture, tmp.resolve("project"));
        Path init = GradleFixtures.initScript(tmp);
        Path out = tmp.resolve("deps.json");

        var args = new java.util.ArrayList<>(List.of(
                "--init-script", init.toAbsolutePath().toString(),
                "marshalDeps",
                "-PmarshalOut=" + out.toAbsolutePath(),
                "--stacktrace"));
        if (includeTest) {
            args.add("-PmarshalIncludeTest=true");
        }

        BuildResult result = GradleRunner.create()
                .withGradleVersion(gradleVersion)
                .withProjectDir(project.toFile())
                .withArguments(args)
                .forwardOutput()
                .build();

        assertThat(result.task(":marshalDeps")).isNotNull();
        assertThat(Files.exists(out))
                .as("marshalDeps must write its output file").isTrue();
        return GradleFixtures.gavs(out);
    }
}
