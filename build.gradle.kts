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
    }
    tasks.named<Test>("test") { useJUnitPlatform() }
}
