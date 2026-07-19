package dev.marshalhq.core;

/**
 * One hop on an introduced-by path: the ordered chain from a declared direct
 * dependency down to a flagged (possibly transitive) node. {@code direct} is true
 * when this GA is declared in the user's own build file.
 */
public record DependencyPathNode(String groupId, String artifactId, String version, boolean direct) {

    public String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public String toGa() {
        return groupId + ":" + artifactId;
    }
}
