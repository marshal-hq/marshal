# Marshal

Behavioral security monitoring for software dependencies.

**WATCH. ANALYZE. BLOCK.**

Marshal watches Maven Central, npm, and PyPI for behavioral signals of
supply-chain attacks and blocks them at PR time, before they reach your build.

## Status

Building in public. v0.1.0 coming in 30 days.

Follow progress: [marshalhq.dev](https://marshalhq.dev)

## What Marshal detects

- Maintainer account changes
- Suspicious install scripts added
- Obfuscated code (eval, base64, hex strings)
- GPG signature drops
- Sudden version jumps
- Dependency count explosions
- New outbound network calls in install scripts

## License

Apache 2.0
