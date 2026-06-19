// A partial failure: the build is valid but one declared dependency exists
// nowhere. resolutionResult is lenient, so it does not fail the build; the init script
// must emit the dependency with an UNRESOLVED sentinel rather than dropping it (which
// would false-clean). Parity with the Maven UNRESOLVED-version path (S06/S13/S15).
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:33.0.0-jre")               // resolves
    implementation("com.example.marshal.nonexistent:does-not-exist:9.9.9") // does not
}
