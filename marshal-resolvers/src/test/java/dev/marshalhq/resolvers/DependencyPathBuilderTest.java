package dev.marshalhq.resolvers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.marshalhq.core.DependencyPathNode;

/**
 * Path construction over a hand-built resolved graph — the core semantics both
 * resolvers share: diamond (multiple paths, shortest first, stable tie-break),
 * cycle guard, and the single self-path for declared directs.
 */
class DependencyPathBuilderTest {

    private static DependencyPathNode node(String ga, String version, boolean direct) {
        int colon = ga.indexOf(':');
        return new DependencyPathNode(ga.substring(0, colon), ga.substring(colon + 1), version, direct);
    }

    @Test
    void diamond_twoDirectsReachOneTransitive_bothPathsPresent_shortestDeterministic() {
        // com.a:a and com.b:b are directs; both pull com.t:t.
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.b:b", "2.0.0",
                "com.t:t", "3.0.0");
        Set<String> directs = Set.of("com.a:a", "com.b:b");
        Map<String, Set<String>> edges = Map.of(
                "com.a:a", Set.of("com.t:t"),
                "com.b:b", Set.of("com.t:t"));

        var paths = DependencyPathBuilder.build(versions, directs, edges);

        assertThat(paths.get("com.t:t")).containsExactly(
                List.of(node("com.a:a", "1.0.0", true), node("com.t:t", "3.0.0", false)),
                List.of(node("com.b:b", "2.0.0", true), node("com.t:t", "3.0.0", false)));

        // Tie-break is lexicographic on the GAV sequence: com.a before com.b, stable
        // across runs regardless of input map iteration order.
        assertThat(paths.get("com.t:t").get(0).get(0).groupId()).isEqualTo("com.a");
    }

    @Test
    void deepTransitive_pathCarriesEveryHopInOrder_directFirst() {
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.m:m", "2.0.0",
                "com.t:t", "3.0.0");
        Set<String> directs = Set.of("com.a:a");
        Map<String, Set<String>> edges = Map.of(
                "com.a:a", Set.of("com.m:m"),
                "com.m:m", Set.of("com.t:t"));

        var paths = DependencyPathBuilder.build(versions, directs, edges);

        assertThat(paths.get("com.t:t")).containsExactly(
                List.of(node("com.a:a", "1.0.0", true),
                        node("com.m:m", "2.0.0", false),
                        node("com.t:t", "3.0.0", false)));
    }

    @Test
    void cycle_inPartialGraph_terminatesWithoutRevisiting() {
        // t1 -> t2 -> t1: possible in a partial graph that never went through conflict
        // resolution. Construction must terminate and each node keeps its acyclic path.
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.t:t1", "1.0.0",
                "com.t:t2", "1.0.0");
        Set<String> directs = Set.of("com.a:a");
        Map<String, Set<String>> edges = Map.of(
                "com.a:a", Set.of("com.t:t1"),
                "com.t:t1", Set.of("com.t:t2"),
                "com.t:t2", Set.of("com.t:t1"));

        var paths = DependencyPathBuilder.build(versions, directs, edges);

        assertThat(paths.get("com.t:t1")).containsExactly(
                List.of(node("com.a:a", "1.0.0", true), node("com.t:t1", "1.0.0", false)));
        assertThat(paths.get("com.t:t2")).containsExactly(
                List.of(node("com.a:a", "1.0.0", true),
                        node("com.t:t1", "1.0.0", false),
                        node("com.t:t2", "1.0.0", false)));
    }

    @Test
    void declaredDirect_hasExactlyOneSelfPath_evenWhenAlsoReachedTransitively() {
        // b is a direct AND a transitive of a: its canonical path stays [b] alone, and
        // b's own children path from b, not from a-through-b (nearest declared direct).
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.b:b", "2.0.0",
                "com.t:t", "3.0.0");
        Set<String> directs = Set.of("com.a:a", "com.b:b");
        Map<String, Set<String>> edges = Map.of(
                "com.a:a", Set.of("com.b:b"),
                "com.b:b", Set.of("com.t:t"));

        var paths = DependencyPathBuilder.build(versions, directs, edges);

        assertThat(paths.get("com.b:b")).containsExactly(
                List.of(node("com.b:b", "2.0.0", true)));
        assertThat(paths.get("com.t:t")).containsExactly(
                List.of(node("com.b:b", "2.0.0", true), node("com.t:t", "3.0.0", false)));
    }

    @Test
    void nodeUnreachableFromAnyDirect_hasNoEntry() {
        // Mirrors the unexpanded-subtree boundary: a node present in the flat set but
        // with no recorded edge from any direct gets no synthesized path.
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.x:orphan", "9.9.9");
        Set<String> directs = Set.of("com.a:a");

        var paths = DependencyPathBuilder.build(versions, directs, Map.of());

        assertThat(paths).containsOnlyKeys("com.a:a");
    }

    @Test
    void unresolvedDirect_selfPathCarriesSentinelVersion() {
        Map<String, String> versions = Map.of("com.a:a", "UNRESOLVED");
        var paths = DependencyPathBuilder.build(versions, Set.of("com.a:a"), Map.of());

        assertThat(paths.get("com.a:a")).containsExactly(
                List.of(node("com.a:a", "UNRESOLVED", true)));
    }

    @Test
    void identicalPathsViaDifferentConfigurations_dedupe() {
        // The same edge reported twice (e.g. compile + runtime classpath) must not
        // produce duplicate paths — edges are a set, and paths dedupe on equality.
        Map<String, String> versions = Map.of(
                "com.a:a", "1.0.0",
                "com.t:t", "3.0.0");
        var paths = DependencyPathBuilder.build(versions, Set.of("com.a:a"),
                Map.of("com.a:a", Set.of("com.t:t")));

        assertThat(paths.get("com.t:t")).hasSize(1);
    }
}
