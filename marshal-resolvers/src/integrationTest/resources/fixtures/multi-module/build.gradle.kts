// Multi-module with a root build file. Proves allprojects iteration and
// cross-module dedup (both subprojects declare guava → one GAV in the output).
subprojects {
    apply(plugin = "java")
    repositories {
        mavenCentral()
    }
}
