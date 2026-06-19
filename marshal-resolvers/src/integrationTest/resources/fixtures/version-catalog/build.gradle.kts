// The type-safe `libs` accessor from gradle/libs.versions.toml is stable from
// Gradle 7.4 — the catalog test pins its floor accordingly (see InitScriptMatrixIT).
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.guava)
}
