// Kotlin DSL parity with the Groovy fixture: same dependencies, must resolve identically.
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:33.0.0-jre")
    implementation("org.apache.commons:commons-lang3:3.14.0")
}
