# Marshal

Behavioral supply-chain security for JVM dependencies.

Marshal watches how packages change on Maven Central — maintainer swaps,
signature drops, dependency explosions — and scores every update on a
0–100 risk scale. Risky updates fail your PR check with a clear reason.
Safe updates pass silently. It's for Java and Gradle teams who auto-merge
dependency updates and want to catch malicious ones before they reach the
build.

![PR comment showing Marshal findings](docs/images/pr-comment.png)

## Quick start

Add this to your repo at `.github/workflows/marshal.yml`:

```yaml
name: Marshal

on:
  pull_request:
    paths: ['pom.xml', 'build.gradle', 'build.gradle.kts']

jobs:
  marshal:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      pull-requests: write
    steps:
      - uses: actions/checkout@v4
      - name: Marshal scan
        uses: marshal-hq/marshal/marshal-action@v0.1.0
        with:
          pom-path: pom.xml
          threshold: red
          fail-on: fail
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Or run the CLI directly:

```bash
# Download
curl -fsSL https://github.com/marshal-hq/marshal/releases/download/v0.1.0/marshal-cli-0.1.0.jar \
  -o marshal.jar

# Scan a project
java -jar marshal.jar scan --pom pom.xml
```

## What it catches

| Signal | What it means |
|--------|---------------|
| Signature dropped | Package was GPG-signed in prior releases, now it's not |
| Missing signature | This release has no GPG signature |
| New maintainer | Different signing key or publisher account from prior version |
| Dependency explosion | Dependency count grew more than 3× in one release |
| Major version jump | Version jumped by more than 2 major versions |
| Repo URL changed | Source repository URL is different from prior version |
| Yanked version | Package was yanked or retracted after publication |

Each update gets a 0–100 risk score. Safe updates pass silently. Risky
ones show up as a PR comment with the evidence and a recommendation.

## Real-world coverage

Marshal's detection engine has been tested against historical supply-chain attacks:

**event-stream (npm, 2018)** — A new maintainer published a version with a
malicious dependency (flatmap-stream) and no GPG signature. Marshal's
NEW_MAINTAINER, DEPENDENCY_EXPLOSION, and SIGNATURE_DROPPED signals fire,
scoring RED.

**ua-parser-js (npm, 2021)** — Account takeover. The attacker published from a
different key. NEW_MAINTAINER and SIGNATURE_DROPPED fire, scoring RED.

**node-ipc protestware (npm, 2022)** — The legitimate maintainer added a
destructive payload, dependency count ballooned, and the version was later
yanked. SIGNATURE_DROPPED, DEPENDENCY_EXPLOSION, and YANKED_VERSION fire.

**PyTorch-nightly dependency confusion (2022)** — A malicious package was
published to PyPI before the legitimate one. First publish with no signature
and later yanked: MISSING_SIGNATURE and YANKED_VERSION score YELLOW.

**XZ Utils (2024)** — Slow social engineering over two years. Marshal would
have flagged the initial maintainer handoff (NEW_MAINTAINER, partial signal).
But XZ was designed to evade automated detection — the attacker spent months
building trust before introducing the backdoor. We're honest about that limit.

These incidents are tested as fixtures in CI. Every release is verified against
them.

## How it works

1. **WATCH** — Marshal fetches version history and metadata for every dependency
   in your project from Maven Central.
2. **ANALYZE** — Each new version is scored against 7 behavioral signals:
   maintainer changes, signature drops, dependency explosions, and more.
3. **BLOCK** — Risky updates fail your PR check with a clear reason.
   Safe updates pass silently.

## What Marshal is not

Marshal is not a CVE scanner — it doesn't look up known vulnerabilities.
Tools like Snyk, Dependabot, and OWASP Dependency-Check already do that well.
Marshal catches the things they structurally can't: the malicious update
that hasn't been reported yet. You probably want to run Marshal alongside
your existing CVE scanner, not instead of it.

## Configuration

Place `marshal.yml` at your project root to customize behavior:

```yaml
rules:
  disabled: []
  overrides: {}

thresholds:
  fail-on: red      # red | orange | yellow
  warn-on: orange

allowlist:
  packages: []
    # - "org.springframework:*"

notifications:
  slack:
    webhook: ${MARSHAL_SLACK_WEBHOOK}
    min-level: red
```

Full reference: [examples/marshal.yml](examples/marshal.yml). Rules can be
disabled individually. Allowlisted packages are skipped entirely. Slack
alerts fire when findings reach or exceed `min-level`.

## Status

Marshal is in early development (v0.1.0). The detection engine covers Maven
Central with 7 behavioral rules. npm and PyPI support are planned.

What works:
- CLI scanning of Maven/Gradle projects
- GitHub Action with PR comments
- Risk scoring with configurable thresholds
- Slack alerts on critical findings

What's next:
- Transitive dependency resolution (currently direct deps only)
- npm ecosystem support
- Hosted continuous watching (SaaS)

## License

Apache 2.0 — see [LICENSE](LICENSE).

## Links

- Website: [marshalhq.dev](https://marshalhq.dev)
- Twitter: [@marshal_hq](https://x.com/marshal_hq)
- Issues: [GitHub Issues](https://github.com/marshal-hq/marshal/issues)
