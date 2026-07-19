package dev.marshalhq.resolvers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import dev.marshalhq.core.DependencyPathNode;

/**
 * Builds introduced-by paths from the parent/child edges of a resolved dependency
 * graph. Shared by both resolvers so Maven and Gradle emit identical paths for the
 * same graph (the S17 parity guarantee extends to path data).
 *
 * <p>Semantics (per node, keyed by {@code group:artifact}):
 * <ul>
 *   <li>A declared direct dependency has exactly one path: itself, {@code direct=true}.</li>
 *   <li>A transitive node's paths are its parents' paths with itself appended. Because a
 *       direct's only path is itself, every path starts at the <em>nearest</em> declared
 *       direct — paths never extend through an intermediate direct.</li>
 *   <li>Identical paths are deduped; ordering is deterministic: fewest nodes first, ties
 *       broken lexicographically on the GAV sequence.</li>
 *   <li>Cycles (possible in partial/malformed graphs that never went through conflict
 *       resolution) are guarded: a node already on the current path is never re-entered.</li>
 * </ul>
 */
final class DependencyPathBuilder {

    private DependencyPathBuilder() {
    }

    /**
     * @param versionByGa   every node in the resolved graph: {@code group:artifact} → the
     *                      version to render (winning version, or a sentinel)
     * @param directGas     GAs declared directly in the user's build file
     * @param childrenByGa  parent GA → child GAs (edges of the resolved graph); endpoints
     *                      not present in {@code versionByGa} are ignored
     * @return GA → all paths from a declared direct to that node, shortest first. GAs with
     *         no path (unreachable from any direct) are absent.
     */
    static Map<String, List<List<DependencyPathNode>>> build(
            Map<String, String> versionByGa,
            Set<String> directGas,
            Map<String, Set<String>> childrenByGa) {

        Map<String, Set<List<String>>> rawPaths = new LinkedHashMap<>();
        for (String root : new TreeSet<>(directGas)) {
            if (!versionByGa.containsKey(root)) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            path.addLast(root);
            walk(root, versionByGa, directGas, childrenByGa, path, rawPaths);
        }

        Map<String, List<List<DependencyPathNode>>> out = new TreeMap<>();
        for (String ga : versionByGa.keySet()) {
            if (directGas.contains(ga)) {
                out.put(ga, List.of(List.of(toNode(ga, versionByGa, directGas))));
                continue;
            }
            Set<List<String>> paths = rawPaths.get(ga);
            if (paths == null || paths.isEmpty()) {
                continue;
            }
            out.put(ga, paths.stream()
                    .sorted(Comparator.<List<String>>comparingInt(List::size)
                            .thenComparing(p -> String.join("|", p)))
                    .map(p -> toNodes(p, versionByGa, directGas))
                    .collect(Collectors.toUnmodifiableList()));
        }
        return out;
    }

    private static void walk(String ga,
            Map<String, String> versionByGa,
            Set<String> directGas,
            Map<String, Set<String>> childrenByGa,
            Deque<String> path,
            Map<String, Set<List<String>>> rawPaths) {

        for (String child : new TreeSet<>(childrenByGa.getOrDefault(ga, Set.of()))) {
            // A direct child's canonical path is itself; a node already on the current
            // path is a cycle (partial graphs only) — never re-enter either.
            if (directGas.contains(child) || !versionByGa.containsKey(child) || path.contains(child)) {
                continue;
            }
            path.addLast(child);
            rawPaths.computeIfAbsent(child, k -> new LinkedHashSet<>()).add(List.copyOf(path));
            walk(child, versionByGa, directGas, childrenByGa, path, rawPaths);
            path.removeLast();
        }
    }

    private static List<DependencyPathNode> toNodes(List<String> gas,
            Map<String, String> versionByGa, Set<String> directGas) {
        List<DependencyPathNode> nodes = new ArrayList<>(gas.size());
        for (String ga : gas) {
            nodes.add(toNode(ga, versionByGa, directGas));
        }
        return List.copyOf(nodes);
    }

    private static DependencyPathNode toNode(String ga,
            Map<String, String> versionByGa, Set<String> directGas) {
        int colon = ga.indexOf(':');
        return new DependencyPathNode(ga.substring(0, colon), ga.substring(colon + 1),
                versionByGa.get(ga), directGas.contains(ga));
    }
}
