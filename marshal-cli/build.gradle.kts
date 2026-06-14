plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("info.solidsoft.pitest") version "1.15.0"
}

configurations {
    create("integrationTestImplementation") {
        extendsFrom(configurations.testImplementation.get())
    }
    create("integrationTestRuntimeOnly") {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
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

dependencies {
    implementation(project(":marshal-core"))
    implementation(project(":marshal-resolvers"))
    implementation(project(":marshal-registry"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.17.0")
    implementation("info.picocli:picocli:4.7.5")
    annotationProcessor("info.picocli:picocli-codegen:4.7.5")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")
}

tasks.jar {
    manifest { attributes["Main-Class"] = "dev.marshalhq.cli.MarshalCli" }
}

tasks.shadowJar {
    archiveBaseName.set("marshal-cli")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

pitest {
    junit5PluginVersion.set("1.2.1")
    targetClasses.set(setOf("dev.marshalhq.cli.*"))
    threads.set(2)
    outputFormats.set(setOf("HTML"))
    timeoutConstInMillis.set(10000)
    timestampedReports.set(false)
}
