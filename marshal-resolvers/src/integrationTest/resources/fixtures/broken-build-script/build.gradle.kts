// A total failure: the build script itself does not compile, so configuration
// fails and marshalDeps never runs. This must surface as could-not-analyze (exit 3),
// never a clean or empty result (S06). (Contrast the unresolvable-dependency fixture: a single unresolvable dependency
// is a PARTIAL failure → an UNRESOLVED entry, not exit 3.)
plugins {
    java
}

repositories {
    mavenCentral()
}

// Deliberate compile error: referencing an undefined symbol fails script compilation.
thisSymbolDoesNotExist()
