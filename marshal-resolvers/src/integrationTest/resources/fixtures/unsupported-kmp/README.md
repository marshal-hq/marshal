# Unsupported in v1: Kotlin Multiplatform

KMP projects declare dependencies in source-set configurations such as
`jvmCompileClasspath` / `commonMainImplementation`, NOT the plain `compileClasspath` /
`runtimeClasspath` the Marshal init script reads.

**Known limitation (a real gap, not a clean result):** because the init script keys on
`compileClasspath` / `runtimeClasspath`, a KMP project currently resolves to an EMPTY
set — i.e. Marshal would report no dependencies even though the project has some. This
is a *false-clean* and is exactly why KMP is declared unsupported in v1. Supporting KMP
means teaching the init script the multiplatform source-set configuration names (see
S17 / deferred work), at which point this limitation flips and its guard test changes.

No runnable fixture is committed (the Kotlin multiplatform plugin must be downloaded and
configured); routing is pinned by a unit test (`BuildToolDetectorTest`).
