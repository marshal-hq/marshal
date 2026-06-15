plugins {
    java
}

allprojects {
    group = "dev.marshalhq"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
        "testImplementation"("org.assertj:assertj-core:3.25.3")
        "testImplementation"("org.mockito:mockito-core:5.11.0")
        "testImplementation"("org.mockito:mockito-junit-jupiter:5.11.0")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.10.2")
    }
    tasks.named<Test>("test") {
        useJUnitPlatform()
        enabled = !project.hasProperty("skipTests")
    }
}

// ── dist ─────────────────────────────────────────────────────────────────────
// Copies the runnable shadowJar to dist/ in the project root after every build.
val distDir = layout.projectDirectory.dir("dist")

val dist by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the marshal-cli fat JAR to dist/"
    dependsOn(":marshal-cli:shadowJar")
    from(project(":marshal-cli").tasks.named("shadowJar"))
    into(distDir)
}

tasks.named("build") { finalizedBy(dist) }
