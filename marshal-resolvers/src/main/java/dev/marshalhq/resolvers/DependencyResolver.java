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

    /**
     * GAVs from the last {@link #resolve(Path)} whose own dependency descriptor could not
     * be read: the node itself is present (and scored), but its transitive subtree was
     * never walked, so anything beneath it is unscanned. Callers must surface these —
     * an unscanned subtree silently reported clean is a false negative (S06). Default is
     * empty for resolvers whose build tool fails resolution outright on a broken module
     * (Gradle), making a silently-unexpanded subtree impossible.
     */
    default List<Coordinates> unexpandedSubtrees() {
        return List.of();
    }
}
