// Dependency locking is on for the two configurations Marshal resolves, and a
// committed gradle.lockfile pins the versions. slf4j-api is transitive-free so the
// lockfile stays trivial and stable. Marshal reads the resolutionResult, so it resolves
// the same whether or not locking is engaged.
plugins {
    java
}

repositories {
    mavenCentral()
}

configurations {
    compileClasspath { resolutionStrategy.activateDependencyLocking() }
    runtimeClasspath { resolutionStrategy.activateDependencyLocking() }
}

dependencies {
    implementation("org.slf4j:slf4j-api:2.0.12")
}
