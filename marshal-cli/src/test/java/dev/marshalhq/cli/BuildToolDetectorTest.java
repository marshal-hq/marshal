package dev.marshalhq.cli;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildToolDetectorTest {

    /**
     * The discriminating case: a multi-module repo whose root has only
     * settings.gradle.kts and NO root build file. Detecting on the build file alone
     * would misroute this (the trap from the prior iteration); it must read as Gradle.
     */
    @Test
    void settingsOnlyRootIsGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.gradle.kts"),
                "rootProject.name = \"app\"\ninclude(\"web\", \"core\")\n");
        Path module = Files.createDirectory(dir.resolve("web"));
        Files.writeString(module.resolve("build.gradle.kts"), "");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
        assertThat(BuildToolDetector.isMavenProject(dir)).isFalse();
    }

    @Test
    void wrapperOnlyRootIsGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\n");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }

    @Test
    void rootBuildFileIsGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }

    @Test
    void kotlinBuildFileIsGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), "");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }

    @Test
    void groovySettingsOnlyIsGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'app'\n");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }

    @Test
    void pomAndGradleBothPresent_detectedAsBoth(@TempDir Path dir) throws Exception {
        // The detector reports both true; the routing layer (ResolverRouter) is what
        // resolves the tie to Maven + a warning — pinned in ResolverRouterTest.
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("build.gradle.kts"), "");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
        assertThat(BuildToolDetector.isMavenProject(dir)).isTrue();
    }

    @Test
    void pomOnlyIsMavenNotGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        assertThat(BuildToolDetector.isMavenProject(dir)).isTrue();
        assertThat(BuildToolDetector.isGradleProject(dir)).isFalse();
    }

    @Test
    void emptyDirIsNeither(@TempDir Path dir) {
        assertThat(BuildToolDetector.isGradleProject(dir)).isFalse();
        assertThat(BuildToolDetector.isMavenProject(dir)).isFalse();
    }

    /**
     * Unsupported in v1: Android and Kotlin-Multiplatform projects are still Gradle
     * builds, so the detector must route them to Gradle rather than silently skip them.
     * Their dependencies may be captured only partially (Android) or not at all (KMP);
     * that limitation is documented under the unsupported-* fixtures, but detection must
     * not pretend they are not Gradle.
     */
    @Test
    void androidProjectStillRoutesToGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"),
                "plugins { id(\"com.android.application\") version \"8.0.0\" }\n");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }

    @Test
    void kmpProjectStillRoutesToGradle(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"),
                "plugins { kotlin(\"multiplatform\") version \"1.9.0\" }\n");

        assertThat(BuildToolDetector.isGradleProject(dir)).isTrue();
    }
}
