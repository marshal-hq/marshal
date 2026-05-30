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
    implementation("org.slf4j:slf4j-api:2.0.12")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
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
