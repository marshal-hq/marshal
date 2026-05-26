dependencies {
    implementation(project(":marshal-core"))
    implementation("org.apache.maven.resolver:maven-resolver-supplier:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-api:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.20")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.20")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")
    implementation("org.apache.maven:maven-model:3.9.6")
    implementation("org.apache.maven:maven-model-builder:3.9.6")
    implementation("org.slf4j:slf4j-api:2.0.12")
}
