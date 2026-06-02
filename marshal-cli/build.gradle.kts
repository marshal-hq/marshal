plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":marshal-core"))
    implementation(project(":marshal-resolvers"))
    implementation(project(":marshal-registry"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
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
