package dev.marshalhq.core;

public record Coordinates(String groupId, String artifactId, String version) {

    public String toGav() {
        return groupId + ":" + artifactId + ":" + version;
    }

    public String toGa() {
        return groupId + ":" + artifactId;
    }
}
