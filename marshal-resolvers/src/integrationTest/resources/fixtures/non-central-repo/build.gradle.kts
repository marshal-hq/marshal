// A dependency that resolves from a non-Central repository (Spring milestones).
// The resolver's job is only to emit the coordinate with its resolved version; whether
// it exists on Maven Central is decided downstream by the registry client (a 404 there
// must read as "not analyzed", never clean — asserted in the marshal-registry tests).
//
// Milestone artifacts can be pruned over time; if this version 404s, bump it to a
// current Spring milestone. That is an honest, loud failure, not a silent miss.
plugins {
    java
}

repositories {
    mavenCentral()
    maven { url = uri("https://repo.spring.io/milestone") }
}

dependencies {
    implementation("org.springframework:spring-core:6.2.0-M1")
}
