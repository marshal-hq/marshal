// Only test dependencies. Default scan resolves a genuine empty set (exit 0,
// not a failure); opting test scopes in surfaces the test dep.
plugins {
    java
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.assertj:assertj-core:3.25.3")
}
