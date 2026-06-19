package dev.marshalhq.cli;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Classifies a project directory as a Gradle and/or Maven build.
 *
 * <p>The Gradle signal deliberately includes {@code settings.gradle(.kts)} and the
 * {@code gradlew} wrapper, not just a root {@code build.gradle(.kts)}. A common ICP
 * layout — a multi-module Spring Boot repo — has only {@code settings.gradle.kts} at
 * the root and no root build file; keying on the build file alone misroutes it.
 */
final class BuildToolDetector {

    // Order is not significant; presence of any marker means "this is a Gradle project."
    private static final String[] GRADLE_MARKERS = {
            "settings.gradle.kts",
            "settings.gradle",
            "build.gradle.kts",
            "build.gradle",
            "gradlew",
    };

    private BuildToolDetector() {
    }

    static boolean isGradleProject(Path dir) {
        for (String marker : GRADLE_MARKERS) {
            if (Files.isRegularFile(dir.resolve(marker))) {
                return true;
            }
        }
        return false;
    }

    static boolean isMavenProject(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"));
    }
}
