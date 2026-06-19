dependencies {
    // depends on the sibling project (a ProjectComponentIdentifier — must be skipped)
    "implementation"(project(":core"))
    // same external GAV as :core — must dedupe to a single coordinate
    "implementation"("com.google.guava:guava:33.0.0-jre")
    "implementation"("org.apache.commons:commons-lang3:3.14.0")
}
