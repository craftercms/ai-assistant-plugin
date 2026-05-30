#!/usr/bin/env bash
set -euo pipefail

# Full tool + intent-recipe matrix against live Studio (long-running).
#
# Offline (no Studio):
#   node scripts/test/functional/tool-id-parity.mjs
#   node scripts/test/functional/recipe-catalog-offline.mjs
#   node scripts/test/functional/generate-tool-recipe-scenarios.mjs --check
#
# Live matrix (requires JWT + LLM keys + intentRecipeRouting.enabled on site):
#   ./scripts/test/functional/run-tool-recipe-matrix.sh
#
# Env:
#   CHAT_SITE_ID / INTEGRATION_SITE_ID — target site (default aiat-2)
#   CHAT_SKIP_OPTIONAL=1 — skip all optional turns (default 0: run integration optional tools)
#   CHAT_MATRIX_FULL=1 — run all write/publish optional turns (set by run-all.sh step 5)
#   CHAT_MATRIX_ALLOW_WRITES=1 — enable write/update/revert/translate optional turns (also set by run-all)
#   CHAT_MATRIX_ALLOW_PUBLISH=1 — enable publish_* recipe/tool turns (also set by run-all)
#   Integration optional tools (SerpAPI, Slack, CrafterQ, GenerateImage, …) run by default;
#   missing keys/secrets count as partial failure (exit 0) — see partialOnMissingConfig in fixtures.
#   CHAT_PREVIEW_TOKEN — recommended for GetPreviewHtml / preview-anchored recipes
#   CHAT_TURN_TIMEOUT_MS — per turn (default 180000; matrix may need 300000)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
# shellcheck source=../../lib/studio-auth.sh
source "${REPO_ROOT}/scripts/lib/studio-auth.sh"

if ! command -v node >/dev/null 2>&1; then
  echo "node required" >&2
  exit 1
fi

echo "======== Offline: tool-id-parity ========"
node "${REPO_ROOT}/scripts/test/functional/tool-id-parity.mjs"

echo "======== Offline: recipe-catalog ========"
node "${REPO_ROOT}/scripts/test/functional/recipe-catalog-offline.mjs"

echo "======== Offline: scenario fixture drift ========"
node "${REPO_ROOT}/scripts/test/functional/generate-tool-recipe-scenarios.mjs" --check

if [[ "${RUN_TOOL_RECIPE_MATRIX_OFFLINE_ONLY:-}" == "1" ]]; then
  echo "======== Skipping live matrix (RUN_TOOL_RECIPE_MATRIX_OFFLINE_ONLY=1) ========"
  exit 0
fi

if ! studio_require_token; then
  echo "No CRAFTER_STUDIO_TOKEN — offline checks passed; skipping live matrix." >&2
  exit 0
fi

export CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
export CHAT_SITE_ID="${CHAT_SITE_ID:-${INTEGRATION_SITE_ID:-aiat-2}}"
export CHAT_SKIP_OPTIONAL="${CHAT_SKIP_OPTIONAL:-0}"
if [[ "${CHAT_SKIP_OPTIONAL}" == "1" ]]; then
  echo "Note: CHAT_SKIP_OPTIONAL=1 — skipping optional turns. Unset or CHAT_SKIP_OPTIONAL=0 to run integration optional tools." >&2
fi
export CHAT_TURN_TIMEOUT_MS="${CHAT_TURN_TIMEOUT_MS:-300000}"
if [[ "${CHAT_MATRIX_FULL:-}" == "1" ]]; then
  echo "Matrix mode: CHAT_MATRIX_FULL=1 (all recipes + tools including write/publish optional turns)" >&2
fi

RUNNER="${REPO_ROOT}/scripts/test/functional/run-chat-scenarios.mjs"

echo ""
echo "======== Live: all intent recipes (${CHAT_SITE_ID}) ========"
export CHAT_SCENARIO_GROUP=intent-recipes
export RUN_ALL_REPORT_SUITE="step5-matrix/intent-recipes"
node "${RUNNER}" "${REPO_ROOT}/scripts/test/scenarios/intent-recipes-all.json"

echo ""
echo "======== Live: all built-in tools (${CHAT_SITE_ID}) ========"
export CHAT_SCENARIO_GROUP=builtin-tools
export RUN_ALL_REPORT_SUITE="step5-matrix/builtin-tools"
node "${RUNNER}" "${REPO_ROOT}/scripts/test/scenarios/tools-all.json"

echo ""
echo "======== Tool/recipe matrix finished ========"
