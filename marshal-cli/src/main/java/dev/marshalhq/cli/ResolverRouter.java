package dev.marshalhq.cli;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.marshalhq.core.config.MarshalConfig;
import dev.marshalhq.resolvers.DependencyResolver;
import dev.marshalhq.resolvers.DependencyScope;
import dev.marshalhq.resolvers.GradleDependencyResolver;
import dev.marshalhq.resolvers.PomDependencyResolver;

/**
 * Maps a path (a build file or a project directory) to the resolver that can
 * handle it, using {@link BuildToolDetector}. Shared by {@code scan} and
 * {@code diff} so detection lives once and never forks across surfaces.
 *
 * <p>Routing rule, identical to {@code scan}: a {@code pom.xml} (or any non-Gradle
 * file) → Maven; a {@code build.gradle(.kts)} file → Gradle; a directory →
 * {@link BuildToolDetector} auto-detection.
 */
final class ResolverRouter {

    record Routed(DependencyResolver resolver, Path target) {}

    private ResolverRouter() {
    }

    /**
     * Routes a single path. Returns {@code null} after printing an error to
     * {@code err} when no build tool could be selected.
     */
    static Routed forPath(Path path, MarshalConfig config, boolean noDaemon, PrintWriter err) {
        if (Files.isDirectory(path)) {
            return autoDetect(path, config, noDaemon, err);
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) {
            return new Routed(gradle(config, noDaemon), path);
        }
        // A file path that is not a Gradle build file is treated as a Maven pom
        // (backward compatible with `--base/--head some/pom.xml`).
        return new Routed(maven(config), path);
    }

    /**
     * Auto-detects the build tool in {@code dir}. When both are present, Maven
     * wins (a note is printed); returns {@code null} after an error when neither
     * is found.
     */
    static Routed autoDetect(Path dir, MarshalConfig config, boolean noDaemon, PrintWriter err) {
        boolean isGradle = BuildToolDetector.isGradleProject(dir);
        boolean isMaven = BuildToolDetector.isMavenProject(dir);
        if (isGradle && isMaven) {
            err.println("Both a Maven (pom.xml) and a Gradle build were found in " + dir
                    + "; using pom.xml. Use a build.gradle path to force Gradle.");
            return new Routed(maven(config), dir.resolve("pom.xml"));
        }
        if (isGradle) {
            return new Routed(gradle(config, noDaemon), dir);
        }
        if (isMaven) {
            return new Routed(maven(config), dir.resolve("pom.xml"));
        }
        err.println("No Maven (pom.xml) or Gradle (settings.gradle(.kts)/build.gradle(.kts)) "
                + "build found in " + dir);
        return null;
    }

    static PomDependencyResolver maven(MarshalConfig config) {
        return new PomDependencyResolver(DependencyScope.fromNames(config.getScan().getScopes()));
    }

    static GradleDependencyResolver gradle(MarshalConfig config, boolean noDaemon) {
        return new GradleDependencyResolver(
                DependencyScope.fromNames(config.getScan().getScopes()), noDaemon);
    }
}
