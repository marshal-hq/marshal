dependencies {
    implementation(project(":marshal-core"))
    implementation("org.apache.maven.resolver:maven-resolver-api:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-impl:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-connector-basic:1.9.18")
    implementation("org.apache.maven.resolver:maven-resolver-transport-http:1.9.18")
    implementation("org.apache.maven:maven-resolver-provider:3.9.6")
    implementation("org.slf4j:slf4j-api:2.0.12")
}
