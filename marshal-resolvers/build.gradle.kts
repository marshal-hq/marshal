configurations {
    create("integrationTestImplementation") {
        extendsFrom(configurations.testImplementation.get())
    }
    create("integrationTestRuntimeOnly") {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
}

dependencies {
    implementation(project(":marshal-core"))
    implementation("org.apache.maven.resolver:maven-resolver-supplier:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-api:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")
    implementation("org.apache.maven:maven-model:3.9.6")
    implementation("org.apache.maven:maven-model-builder:3.9.6")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Gradle TestKit drives the init script across the supported Gradle version range
    // (GradleRunner.withGradleVersion). Integration-only; never on the per-push path.
    "integrationTestImplementation"(gradleTestKit())
}

sourceSets {
    create("integrationTest") {
        val main = sourceSets.main.get()
        // Include main's own dependencies (jackson, maven-resolver, …), not just its
        // compiled output, so integration tests can use them directly.
        compileClasspath += main.output + main.compileClasspath
        runtimeClasspath += main.output + main.runtimeClasspath
    }
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against real Maven Central"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
