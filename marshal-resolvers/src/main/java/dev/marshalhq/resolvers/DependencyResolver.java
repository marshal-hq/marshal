package dev.marshalhq.resolvers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.marshalhq.core.Coordinates;
import dev.marshalhq.core.DependencyPathNode;

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

    /**
     * Introduced-by paths from the last {@link #resolve(Path)}, keyed by
     * {@code group:artifact}: every path from a declared direct dependency to that node,
     * shortest first (see {@link DependencyPathBuilder} for ordering/dedup semantics).
     * Display metadata only — scoring never reads it. Default is empty for resolvers
     * (or test doubles) that carry no path data; callers must tolerate absent entries.
     */
    default Map<String, List<List<DependencyPathNode>>> dependencyPaths() {
        return Map.of();
    }
}
