// The com.example:included-lib coordinate is substituted by the included build,
// so it resolves to a project component and must be SKIPPED. The genuine external dep
// (guava) must still be captured.
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.example:included-lib:1.0")
    implementation("com.google.guava:guava:33.0.0-jre")
}
