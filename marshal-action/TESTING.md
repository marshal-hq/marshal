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
| 5 | Shallow checkout | Workflow without `fetch-depth: 0` | Normally still resolves: `run.sh` fetches the base SHA explicitly, which succeeds for a same-repo PR regardless of depth. The honest-failure path (`::error::… base ref … not available … fetch-depth: 0`, exit 3) is defense-in-depth for a genuinely unreachable base (force-pushed/deleted base branch, fork PR without base access). |

Notes:
- Cases 1–4 require `actions/checkout` with `fetch-depth: 0` and, for Gradle, a
  `setup-java` step ahead of the Marshal step (see `examples/workflow-marshal.yml`).
- Case 5 is the negative of that prerequisite and verifies the honest-failure path
  rather than a silent or misleading result.
- The base side runs `./gradlew` in a separate git worktree, so a Gradle diff is two
  builds (§2.3). Expect roughly double a single scan's time in CI.

## Acceptance matrix (live dogfood)

Run against the throwaway repos `marshal-dogfood-gradle` (Gradle) and
`marshal-dogfood` (Maven), each case on its own scratch branch. Judge pass or fail
against the Expected cell, which is grounded in the cited code path, not by feel.
Fill Observed and Result after each run; leave them blank until then.

### Proven (PR #1 / push run)

| Case | Trigger | Expected | Result |
|------|---------|----------|--------|
| Happy path, risky dep | PR bumps `javax.activation` 1.1-rev-1 to 1.1.1 | ORANGE 55 finding, PR comment, exit 1, red check. PR diff path: `DiffCommand.call` classifies the bump as VERSION_CHANGED and `CliHelper.computeExitCode` returns 1; `run.sh:90-93` posts the report comment | PASS (PR #1) |
| Jar download pinned | Normal run | Downloads `marshal-cli-<version>.jar` for the pinned `marshal-version` via `gh release download` (`action.yml:58-69`) | PASS (PR #1) |
| Java 21 detection (hosted) | Normal run | Uses `JAVA_HOME_21_*`; the self-hosted `setup-java` fallback is skipped because `detect-java` reports `needs-setup=false` (`action.yml:40-56`) | PASS (PR #1) |
| Non-PR push fallback | Push to `main` | No PR base, so `run.sh:72-82` logs "No pull-request base; running a full scan of the head" and runs `marshal scan`; clean scan exits 0 | PASS |

### To run

| Case | Trigger | Expected | Observed | Result |
|------|---------|----------|----------|--------|
| 1. Broken build (exit 3) | `build.gradle.kts` with a Kotlin DSL syntax error (total failure) | Exit 3, never green. `GradleDependencyResolver.resolve` throws `ResolutionException` ("could not resolve dependencies"); `DiffCommand.java:128-133` returns 3; `run.sh:99-103` emits `::error::Marshal could not analyze this project: ...` and the comment carries "The check fails on purpose: an unanalyzable build is not a clean build." Check fails **red, never green, never a wall of new findings** | | |
| 2. Partial UNRESOLVED | PR adds `com.example.marshal.nonexistent:does-not-exist:9.9.9` | **NOT exit 3.** `resolutionResult` is lenient, so `resolve` returns a populated list with an `UNRESOLVED` sentinel coordinate (parity with the proven `unresolvable-dependency` integration fixture). `DiffCommand.java:187-194` turns the added dep into `Finding.unresolved`; the markdown renders it as a could-not-resolve / manual-review entry, **visibly distinct from both a clean dep and a risky finding**, never a silent green. Capture the **actual comment wording** in Observed, not just the exit code: confirm it reads as "could not analyze / manual review", not as an ordinary flagged finding | | |
| 3a. No-change, target | PR edits `build.gradle.kts` with no dependency change | Empty diff (no ADDED/VERSION_CHANGED), check passes (exit 0), **no PR comment posted** | | |
| 3b. No-change, observed (gap) | Same PR | Record actual behavior. Likely posts a near-empty "0 dependencies ... safe" comment: `run.sh:90-136` posts/updates on exit 0/1 with no empty-body guard, and `MarkdownReporter.java:66-73` always emits the `<!-- marshal-bot -->` header plus the summary line even when nothing is flagged. Capture whether a comment appears | | |
| 4. Maven regression | Maven PR in `marshal-dogfood` (workflow now `@main`, `fetch-depth: 0`, `marshal-version: 0.2.0-rc.3`, `path: .`) | Runs through the **new composite, not Docker**: same `run.sh` diff path resolves the Maven side via `ResolverRouter`, posts a comment, and maps the exit code correctly. A clean pass proves the regression; the `javax.activation` bump in the pom yields a finding | | |
| 5. Config error (exit 2) | Workflow input `threshold: bogus` | Exit 2, distinct from exit 3. `--threshold BOGUS` reaches `CaseInsensitiveConverter.ForSeverity`, whose `Severity.valueOf` throws; picocli reports a usage error and `execute` returns 2; `run.sh:94-98` emits `::error::Marshal configuration error: ...`. Check fails. (Note: `threshold` is not a `marshal.yml` config field, and a malformed project-root `marshal.yml` is swallowed to defaults by `MarshalConfigLoader.java:67-70`, so the workflow input is the reliable exit-2 trigger) | | |
| Shallow checkout | Remove `fetch-depth: 0` on a scratch branch | For a same-repo PR, **not exit 3**: `run.sh` fetches the base SHA explicitly (`run.sh:54`), which succeeds even on a shallow checkout because the base commit is reachable on origin, so the diff resolves anyway. The exit-3 honest-failure branch is defense-in-depth, reachable only when the base is genuinely unreachable (force-pushed/deleted base, fork PR without base access, network/permission failure) | Same-repo shallow PR resolved correctly, no exit 3 (PR #6) | Confirmed: robustness is intentional (explicit base-SHA fetch), exit-3 is defense-in-depth |
| Branch protection (repo setting) | Failing check on a PR | A non-zero exit fails the check, but blocking the merge requires a branch-protection rule that **requires** the Marshal check. Confirm in repo settings whether the failing check actually blocks merge | | |

### Out of scope (not testable on a hosted runner)

| Case | Why |
|------|-----|
| Self-hosted Java 21 fallback | Hosted runners always expose `JAVA_HOME_21_*`, so `action.yml:51-56` never fires. Code-review item only; cannot be exercised on a hosted runner |

Resolved (Case 3b gap): the empty-diff comment noise is fixed. `MarkdownReporter`
now emits a structured `<!-- marshal:actionable=... -->` marker (true only when
something is flagged, advisory, or unresolved), and `run.sh` reads it to decide
post-or-skip: it stays silent on a clean diff with no prior comment, and updates an
existing Marshal comment to a brief "no current findings" note when a previously
flagged PR becomes clean.
