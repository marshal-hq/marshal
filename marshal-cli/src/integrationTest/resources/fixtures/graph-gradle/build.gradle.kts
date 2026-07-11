// Gradle fixture for the same fixed dependency graph as ../graph-maven/pom.xml:
//
//   project
//   ├── dep-a  ──> dep-x
//   └── dep-b  ──> dep-x, dep-y
//   dep-x  ──> dep-z
//
// Unlike the Maven path, the Gradle resolver runs a real Gradle build and walks the
// full resolved graph, so a real resolution yields all five modules:
// dep-a, dep-b, dep-x, dep-y, dep-z (dep-x/dep-z pinned to one version, no conflict).
//
// The dependencies are served from the committed local Maven repo under ./repo
// (poms only — the init script reads the resolutionResult graph, never artifacts),
// so resolution touches no network. Coordinates are placeholders.

plugins {
    `java-library`
}

group = "com.example.marshaltest"
version = "1.0.0"

repositories {
    // Resolved relative to this project dir; the repo is copied alongside the build.
    maven { url = uri("repo") }
}

dependencies {
    implementation("com.example.marshaltest:dep-a:1.0.0")
    implementation("com.example.marshaltest:dep-b:1.0.0")
}
