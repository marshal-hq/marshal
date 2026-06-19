// One dependency per configuration, each a distinct GAV so scope mapping is
// unambiguous. Default scan = compile+runtime classpaths (api, implementation,
// compileOnly, runtimeOnly). Test deps appear only with -PmarshalIncludeTest=true.
plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api("com.google.guava:guava:33.0.0-jre")                       // compile + runtime
    implementation("org.apache.commons:commons-lang3:3.14.0")      // compile + runtime
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")           // compile only
    runtimeOnly("org.slf4j:slf4j-simple:2.0.12")                   // runtime only
    testImplementation("org.assertj:assertj-core:3.25.3")          // test only
}
