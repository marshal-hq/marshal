#!/bin/bash
# Marshal GitHub Action entrypoint.
# Runs `marshal diff`, posts (or updates) the PR comment, optionally alerts Slack.
set -euo pipefail

# ── inputs (set by action.yml env: block) ──────────────────────────────────────
POM_PATH="${INPUT_POM_PATH:-pom.xml}"
THRESHOLD="${INPUT_THRESHOLD:-red}"
COMMENT_ON_PR="${INPUT_COMMENT_ON_PR:-true}"
FAIL_ON="${INPUT_FAIL_ON:-fail}"
SLACK_WEBHOOK="${INPUT_SLACK_WEBHOOK:-}"

# ── GitHub context (injected automatically by the Actions runner) ───────────────
REPO="${GITHUB_REPOSITORY}"
WORKSPACE="${GITHUB_WORKSPACE}"
EVENT_PATH="${GITHUB_EVENT_PATH}"
# GITHUB_TOKEN is set automatically by the runner for Docker actions.
TOKEN="${GITHUB_TOKEN:-}"

HEAD_POM="${WORKSPACE}/${POM_PATH}"
BASE_POM="/tmp/marshal-base-pom.xml"
REPORT_FILE="/tmp/marshal-report.md"

# ── Picocli enums are case-sensitive — uppercase the user-facing inputs ────────
THRESHOLD_UPPER="$(echo "${THRESHOLD}" | tr '[:lower:]' '[:upper:]')"
FAIL_ON_UPPER="$(echo "${FAIL_ON}" | tr '[:lower:]' '[:upper:]')"

# ── parse PR event ─────────────────────────────────────────────────────────────
PR_NUMBER=$(jq -r '.pull_request.number // .number // empty' "${EVENT_PATH}" 2>/dev/null || echo "")
BASE_SHA=$(jq -r '.pull_request.base.sha // empty' "${EVENT_PATH}" 2>/dev/null || echo "")

# ── fetch base POM via GitHub Contents API (works with shallow checkouts) ──────
if [ -n "${BASE_SHA}" ] && [ -n "${TOKEN}" ]; then
  HTTP_STATUS=$(curl -sf \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github.v3.raw" \
    -o "${BASE_POM}" \
    -w "%{http_code}" \
    "https://api.github.com/repos/${REPO}/contents/${POM_PATH}?ref=${BASE_SHA}" 2>/dev/null || echo "000")

  if [ "${HTTP_STATUS}" != "200" ]; then
    echo "[marshal] Warning: could not fetch base POM at ${BASE_SHA}:${POM_PATH} (HTTP ${HTTP_STATUS}). Treating as empty project." >&2
    echo "<project/>" > "${BASE_POM}"
  fi
else
  echo "[marshal] Warning: BASE_SHA or GITHUB_TOKEN not available — treating base as empty project." >&2
  echo "<project/>" > "${BASE_POM}"
fi

# ── run marshal diff twice (cache makes the second call free) ─────────────────
# Call 1: produce the markdown report with --fail-on NEVER so we always
#         reach the comment + alert steps below regardless of findings.
set +e
marshal diff \
  --base    "${BASE_POM}" \
  --head    "${HEAD_POM}" \
  --output  MD \
  --threshold "${THRESHOLD_UPPER}" \
  --fail-on   NEVER \
  > "${REPORT_FILE}"
REPORT_EXIT=$?

# Call 2: compute the real exit code using the caller's --fail-on setting.
# All metadata is now in the SQLite cache — no HTTP calls, completes in ~ms.
marshal diff \
  --base    "${BASE_POM}" \
  --head    "${HEAD_POM}" \
  --output  JSON \
  --threshold "${THRESHOLD_UPPER}" \
  --fail-on   "${FAIL_ON_UPPER}" \
  > /dev/null
MARSHAL_EXIT=$?
set -e

echo "[marshal] Diff complete (report exit ${REPORT_EXIT}, policy exit ${MARSHAL_EXIT}). Report: ${REPORT_FILE}"

# ── post / update PR comment ───────────────────────────────────────────────────
if [ "${COMMENT_ON_PR}" = "true" ] && [ -n "${TOKEN}" ] && [ -n "${PR_NUMBER}" ]; then

  # Find existing marshal-bot comment (identified by hidden first-line marker)
  EXISTING_ID=$(curl -sf \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Accept: application/vnd.github.v3+json" \
    "https://api.github.com/repos/${REPO}/issues/${PR_NUMBER}/comments?per_page=100" \
    | jq -r '[.[] | select(.body | startswith("<!-- marshal-bot -->"))] | first | .id // ""')

  # Encode report as a JSON string (handles newlines, quotes, special chars)
  BODY=$(jq -Rs . < "${REPORT_FILE}")

  if [ -n "${EXISTING_ID}" ]; then
    echo "[marshal] Updating existing comment ${EXISTING_ID}."
    curl -sf -X PATCH \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Accept: application/vnd.github.v3+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/repos/${REPO}/issues/comments/${EXISTING_ID}" \
      -d "{\"body\": ${BODY}}" > /dev/null
  else
    echo "[marshal] Posting new comment on PR #${PR_NUMBER}."
    curl -sf -X POST \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Accept: application/vnd.github.v3+json" \
      -H "Content-Type: application/json" \
      "https://api.github.com/repos/${REPO}/issues/${PR_NUMBER}/comments" \
      -d "{\"body\": ${BODY}}" > /dev/null
  fi
fi

# ── Slack alert (RED findings only) ───────────────────────────────────────────
if [ -n "${SLACK_WEBHOOK}" ] && grep -q "🔴 HIGH RISK" "${REPORT_FILE}" 2>/dev/null; then
  SLACK_MSG="⚠️ Marshal detected *HIGH RISK* dependency changes in \`${REPO}\` PR #${PR_NUMBER}. Review before merging."
  curl -sf -X POST \
    -H "Content-Type: application/json" \
    "${SLACK_WEBHOOK}" \
    -d "{\"text\": \"${SLACK_MSG}\"}" > /dev/null
  echo "[marshal] Slack alert sent."
fi

# ── apply fail-on policy ───────────────────────────────────────────────────────
# marshal diff already computed whether threshold was breached (MARSHAL_EXIT).
# Re-implement fail-on here so the comment/alert steps always run.
if [ "${FAIL_ON}" = "fail" ] && [ "${MARSHAL_EXIT}" -ne 0 ]; then
  echo "[marshal] Findings at or above threshold '${THRESHOLD}' — failing." >&2
  exit 1
elif [ "${FAIL_ON}" = "warn" ] && [ "${MARSHAL_EXIT}" -ne 0 ]; then
  echo "[marshal] Warning: findings at or above threshold '${THRESHOLD}' detected." >&2
  exit 0
else
  exit 0
fi