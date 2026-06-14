package dev.marshalhq.resolvers;

import java.util.List;
import java.util.Optional;

public enum DependencyScope {
    COMPILE("compile"),
    RUNTIME("runtime"),
    TEST("test"),
    PROVIDED("provided"),
    SYSTEM("system"),
    IMPORT("import");

    private final String mavenName;

    DependencyScope(String mavenName) {
        this.mavenName = mavenName;
    }

    public String mavenName() {
        return mavenName;
    }

    public static Optional<DependencyScope> from(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (DependencyScope scope : values()) {
            if (scope.mavenName.equalsIgnoreCase(name)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }

    public static List<DependencyScope> fromNames(List<String> names) {
        return names.stream()
                .flatMap(s -> from(s).stream())
                .toList();
    }
}
