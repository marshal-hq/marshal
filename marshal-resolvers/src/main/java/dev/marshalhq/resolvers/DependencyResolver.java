package dev.marshalhq.resolvers;

import java.nio.file.Path;
import java.util.List;

import dev.marshalhq.core.Coordinates;

/**
 * Common contract for a build-tool dependency resolver: it consumes a build file
 * (or project directory) and emits the shared {@link Coordinates} type the
 * detection engine scans. Implemented by {@link PomDependencyResolver} (Maven) and
 * {@link GradleDependencyResolver} (Gradle).
 */
public interface DependencyResolver {

    List<Coordinates> resolve(Path buildFile);
}
