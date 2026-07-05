# Marshal demo fixture

This directory contains a curated demo for Marshal that produces mixed
GREEN/ORANGE/RED output without hitting Maven Central.

## What it contains

| Dependency | Version | Expected score | Why |
|-----------|---------|---------------|-----|
| commons-lang3 | 3.14.0 | 0 — GREEN | Same signing key as previous version |
| slf4j-api | 2.0.12 | 0 — GREEN | Same signing key as previous version |
| commons-io | 2.16.1 | 55 — ORANGE | Signature dropped (was signed in 2.15.1) |
| jackson-databind | 2.17.0 | 90 — RED | New maintainer + signature dropped |
| spring-core | 6.1.4 | 0 — GREEN | Same signing key as previous version |

The metadata in `marshal-cache.db` is curated — it does not represent the
actual state of these packages on Maven Central. jackson-databind and
commons-io are not compromised. This is a demo fixture only.

## Running the demo

First, build the CLI:

```bash
./gradlew :marshal-cli:shadowJar
```

Generate (or regenerate) the demo cache:

```bash
java -cp marshal-cli/build/libs/marshal-cli-*.jar dev.marshalhq.cli.DemoCacheBuilder
```

Run the scan:

```bash
java -jar marshal-cli/build/libs/marshal-cli-*.jar \
  scan --source examples/demo/pom.xml
```

## Cache expiry

The cache entries use a far-future timestamp and do not expire. If you
ever need to regenerate the cache (e.g., after modifying DemoCacheBuilder),
delete `marshal-cache.db` and run `DemoCacheBuilder` again.

## Recording the demo GIF

With [vhs](https://github.com/charmbracelet/vhs) installed:

```bash
vhs docs/demo.tape
```

Output: `docs/images/demo.gif`
