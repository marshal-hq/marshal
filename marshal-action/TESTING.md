# Action acceptance tests (§3.9)

The composite action moved from a Docker container to runner-native execution, and
now serves both Maven and Gradle. Because the value is realized in a live runner
(setup-java toolchain, `./gradlew`, the Gradle cache, the GitHub API), these cases
must be exercised in a throwaway repo against the published action, not in unit
tests. Run each once after any change to `action.yml`, `run.sh`, or `publish.yml`.

Prerequisite: publish a release first (`v<version>` tag → `publish.yml` uploads
`marshal-cli-<version>.jar`), then point the throwaway repo's workflow at that
version via `marshal-version`.

| # | Scenario | Setup | Expected |
|---|----------|-------|----------|
| 1 | Gradle PR adds a risky dependency | Gradle repo, PR adds a RED-scoring dep | PR comment posted; check **fails** (exit 1) |
| 2 | Gradle PR, no dependency change | Gradle repo, PR touches only source | Check **passes**, no noise; comment shows empty/clean diff |
| 3 | Gradle PR where head does not build | Gradle repo, PR breaks `build.gradle` | Check **fails** with `::error::Marshal could not analyze…` (exit 3) — **not** green, **not** a wall of "new" findings |
| 4 | Maven PR (regression guard) | Maven repo, PR bumps a dependency | Still works through the composite; comment + correct exit |
| 5 | Shallow checkout | Workflow without `fetch-depth: 0` | Fails honestly: `::error::… base ref … not available … fetch-depth: 0` |

Notes:
- Cases 1–4 require `actions/checkout` with `fetch-depth: 0` and, for Gradle, a
  `setup-java` step ahead of the Marshal step (see `examples/workflow-marshal.yml`).
- Case 5 is the negative of that prerequisite and verifies the honest-failure path
  rather than a silent or misleading result.
- The base side runs `./gradlew` in a separate git worktree, so a Gradle diff is two
  builds (§2.3). Expect roughly double a single scan's time in CI.
