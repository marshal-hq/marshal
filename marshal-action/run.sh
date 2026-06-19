#!/usr/bin/env bash
# Marshal GitHub Action — runner-native orchestration (composite action).
#
# Serves both Maven and Gradle through one path. For a pull_request it builds a
# base working tree and runs `marshal diff`; for other events it runs `marshal scan`
# on the head. Maps the CLI exit code to a check result (§3.6): 0 pass, 1 findings,
# 2 config error, 3 could-not-analyze — the last two as ::error:: annotations so a
# failed analysis is never a silent green check.
set -uo pipefail

# ── inputs (exported by action.yml) ─────────────────────────────────────────────
TARGET_PATH="${INPUT_PATH:-.}"
THRESHOLD="${INPUT_THRESHOLD:-red}"
FAIL_ON="${INPUT_FAIL_ON:-fail}"
COMMENT_ON_PR="${INPUT_COMMENT_ON_PR:-true}"
SLACK_WEBHOOK="${INPUT_SLACK_WEBHOOK:-}"
JAR="${MARSHAL_JAR:?MARSHAL_JAR not set}"

# Java 21 to run the Marshal jar — explicit, never the project's JAVA_HOME (§3.3).
MARSHAL_JAVA_HOME="${JAVA_HOME_21_X64:-${JAVA_HOME_21_ARM64:-${JAVA_HOME:-}}}"
JAVA_BIN="${MARSHAL_JAVA_HOME:+${MARSHAL_JAVA_HOME}/bin/}java"

# Picocli enums are case-sensitive.
THRESHOLD_UPPER="$(echo "${THRESHOLD}" | tr '[:lower:]' '[:upper:]')"
FAIL_ON_UPPER="$(echo "${FAIL_ON}" | tr '[:lower:]' '[:upper:]')"

WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
REPO="${GITHUB_REPOSITORY:-}"
EVENT_PATH="${GITHUB_EVENT_PATH:-}"
TOKEN="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
REPORT_FILE="${RUNNER_TEMP:-/tmp}/marshal-report.md"
ERR_FILE="${RUNNER_TEMP:-/tmp}/marshal-err.txt"

marshal() { "${JAVA_BIN}" -jar "${JAR}" "$@"; }

# Resolve PR context (empty for non-PR events).
PR_NUMBER=""
BASE_SHA=""
if [ -n "${EVENT_PATH}" ] && [ -f "${EVENT_PATH}" ]; then
  PR_NUMBER=$(jq -r '.pull_request.number // empty' "${EVENT_PATH}" 2>/dev/null || echo "")
  BASE_SHA=$(jq -r '.pull_request.base.sha // empty' "${EVENT_PATH}" 2>/dev/null || echo "")
fi

# ── run Marshal ─────────────────────────────────────────────────────────────────
# Single pass: stdout is the report (exit 0/1), stderr carries the reason (exit 2/3).
# fail-on is honored by the CLI, so EXIT already encodes the policy decision — except
# exit 3, which the CLI returns regardless of fail-on (honest failure is not suppressible).
set +e
if [ -n "${BASE_SHA}" ]; then
  # ── PR: materialize the base tree, then diff ──────────────────────────────────
  BASE_DIR="${RUNNER_TEMP:-/tmp}/marshal-base"
  rm -rf "${BASE_DIR}"
  # Default CI checkout is shallow, so the base SHA is usually absent locally (§3.5).
  if ! git -C "${WORKSPACE}" fetch --no-tags --depth=1 origin "${BASE_SHA}" 2>"${ERR_FILE}"; then
    echo "::error::Marshal could not analyze this project: base ref ${BASE_SHA} is not available. Check out with fetch-depth: 0 (actions/checkout) so the PR base can be compared." >&2
    exit 3
  fi
  if ! git -C "${WORKSPACE}" worktree add --detach "${BASE_DIR}" "${BASE_SHA}" 2>"${ERR_FILE}"; then
    echo "::error::Marshal could not analyze this project: failed to materialize base tree at ${BASE_SHA}: $(cat "${ERR_FILE}")" >&2
    exit 3
  fi
  marshal diff \
    --base "${BASE_DIR}/${TARGET_PATH}" \
    --head "${WORKSPACE}/${TARGET_PATH}" \
    --no-daemon \
    --output MD \
    --threshold "${THRESHOLD_UPPER}" \
    --fail-on "${FAIL_ON_UPPER}" \
    >"${REPORT_FILE}" 2>"${ERR_FILE}"
  EXIT=$?
  git -C "${WORKSPACE}" worktree remove --force "${BASE_DIR}" 2>/dev/null || true
else
  # ── non-PR (push, etc.): no base to diff against — scan the head instead (§3.5) ─
  echo "[marshal] No pull-request base; running a full scan of the head."
  marshal scan \
    --pom "${WORKSPACE}/${TARGET_PATH}" \
    --no-daemon \
    --output MD \
    --threshold "${THRESHOLD_UPPER}" \
    --fail-on "${FAIL_ON_UPPER}" \
    >"${REPORT_FILE}" 2>"${ERR_FILE}"
  EXIT=$?
fi
set -e

echo "[marshal] Analysis complete (exit ${EXIT})."

# ── build the comment/annotation body per exit code (§3.6) ──────────────────────
REASON="$(tr -d '\r' < "${ERR_FILE}" | tail -n 20)"
case "${EXIT}" in
  0|1)
    COMMENT_BODY_FILE="${REPORT_FILE}"
    ;;
  2)
    echo "::error::Marshal configuration error: ${REASON}"
    printf '<!-- marshal-bot -->\n### ⚙️ Marshal configuration error\n\n```\n%s\n```\n' "${REASON}" > "${REPORT_FILE}"
    COMMENT_BODY_FILE="${REPORT_FILE}"
    ;;
  3)
    echo "::error::Marshal could not analyze this project: ${REASON}"
    printf '<!-- marshal-bot -->\n### ⚠️ Marshal could not analyze this project\n\n```\n%s\n```\n\nThe check fails on purpose: an unanalyzable build is not a clean build.\n' "${REASON}" > "${REPORT_FILE}"
    COMMENT_BODY_FILE="${REPORT_FILE}"
    ;;
  *)
    echo "::error::Marshal exited unexpectedly (code ${EXIT}): ${REASON}"
    printf '<!-- marshal-bot -->\n### ⚠️ Marshal exited unexpectedly (code %s)\n\n```\n%s\n```\n' "${EXIT}" "${REASON}" > "${REPORT_FILE}"
    COMMENT_BODY_FILE="${REPORT_FILE}"
    ;;
esac

# ── post / update the PR comment (idempotent via the <!-- marshal-bot --> marker) ─
if [ "${COMMENT_ON_PR}" = "true" ] && [ -n "${TOKEN}" ] && [ -n "${PR_NUMBER}" ] && [ -n "${REPO}" ]; then
  BODY=$(jq -Rs . < "${COMMENT_BODY_FILE}")
  EXISTING_ID=$(curl -sf \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${REPO}/issues/${PR_NUMBER}/comments?per_page=100" \
    | jq -r '[.[] | select(.body | startswith("<!-- marshal-bot -->"))] | first | .id // ""' || echo "")
  if [ -n "${EXISTING_ID}" ]; then
    echo "[marshal] Updating PR comment ${EXISTING_ID}."
    curl -sf -X PATCH \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/repos/${REPO}/issues/comments/${EXISTING_ID}" \
      -d "{\"body\": ${BODY}}" > /dev/null
  else
    echo "[marshal] Posting PR comment on #${PR_NUMBER}."
    curl -sf -X POST \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/repos/${REPO}/issues/${PR_NUMBER}/comments" \
      -d "{\"body\": ${BODY}}" > /dev/null
  fi
fi

# ── Slack alert (RED findings only) ─────────────────────────────────────────────
if [ -n "${SLACK_WEBHOOK}" ] && grep -q "🔴 HIGH RISK" "${REPORT_FILE}" 2>/dev/null; then
  SLACK_MSG="⚠️ Marshal detected *HIGH RISK* dependency changes in \`${REPO}\`${PR_NUMBER:+ PR #${PR_NUMBER}}. Review before merging."
  curl -sf -X POST -H "Content-Type: application/json" "${SLACK_WEBHOOK}" \
    -d "{\"text\": \"${SLACK_MSG}\"}" > /dev/null || echo "[marshal] Slack post failed (non-fatal)." >&2
fi

# Propagate the CLI's exit code: 0 pass, 1 findings (fail-on=fail), 2 config, 3 could-not-analyze.
exit "${EXIT}"
