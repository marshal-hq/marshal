# Unsupported in v1: Android / AGP

Android projects (the `com.android.*` plugins) use a different configuration set
(`releaseCompileClasspath`, `debugRuntimeClasspath`, …) than the plain JVM
`compileClasspath` / `runtimeClasspath` the Marshal init script reads. They also
require the Android SDK to even configure.

**Documented behavior:** the detector routes an Android project to Gradle (it is a
Gradle build), but resolution is *not* supported in v1 — it will either fail loudly
(could-not-analyze / exit 3, e.g. missing SDK or AGP) or capture nothing. It must
never silently report a clean result that hides unanalyzed Android dependencies.

No runnable fixture is committed: a real AGP build needs the SDK, so it cannot run in
the nightly matrix. Routing is pinned by a unit test (`BuildToolDetectorTest`).
