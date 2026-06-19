// Group + name (com.example:included-lib) match the requested coordinate, so Gradle
// substitutes the external dependency with this included build automatically.
plugins {
    java
}

group = "com.example"
version = "1.0"

repositories {
    mavenCentral()
}
