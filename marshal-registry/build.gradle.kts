dependencies {
    implementation(project(":marshal-core"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("org.apache.maven:maven-model:3.9.6")
    implementation("org.bouncycastle:bcpg-jdk18on:1.78.1")
}
