// A BOM/platform manages the version; the starter is declared without one.
// The init script must emit a concrete resolved version, never blank/UNRESOLVED.
// (Parity win: Maven leaves BOM-managed versions UNRESOLVED per S06/S13.)
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.2.5"))
    implementation("org.springframework.boot:spring-boot-starter")
}
