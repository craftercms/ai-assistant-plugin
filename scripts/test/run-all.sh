#!/usr/bin/env bash
set -euo pipefail

# Single entrypoint for maintainer checks: shell syntax, same `yarn package` as install-plugin.sh
# (Rollup + form-pipeline verify), live Studio REST JSON contracts, and **by default** scripted **ai/stream**
# chat turns (OpenAI or whatever the Studio JVM is configured to use — not chosen by this script).
# Not part of the Studio plugin artifact. Intentionally does NOT run ESLint unless you opt in.
#
# Usage (repo root or any cwd):
#   ./scripts/test/run-all.sh
#
# Env:
#   RUN_ALL_SKIP_STUDIO=1          Skip live Studio steps (rest-contracts + chat scenarios); bash -n + yarn package only.
#   RUN_ALL_SKIP_CHAT_SCENARIOS=1 Skip only the chat scenario runner (still runs live rest-contracts when Studio is on).
#   RUN_ALL_WITH_LINT=1            Also run `yarn lint` in sources/ before `yarn package`.
#   CRAFTER_STUDIO_URL, INTEGRATION_SITE_ID — passed through when Studio checks run; CHAT_SITE_ID defaults to INTEGRATION_SITE_ID.
#   CHAT_AGENT_ID — optional override; otherwise run-chat-scenarios.mjs discovers ui.xml or uses the default agent UUID.
#   CHAT_SCENARIOS_FILE, CHAT_PREVIEW_TOKEN, CHAT_TURN_TIMEOUT_MS — see scripts/test/README.md.
#   RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=1 Skip the full per-tool + per-recipe live matrix (still runs offline parity in step 1).
#   CHAT_LLM=claude                 Claude smoke profile: chat-scenarios-claude-smoke.json + skip step-5 matrix
#                                   (Tier-1 Sonnet 30k input TPM cannot run the full 31-tool matrix). Override with
#                                   CHAT_CLAUDE_FULL_MATRIX=1 or set CHAT_SCENARIOS_FILE / RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=0.
#   Step 5 runs all 13 recipes + 31 tools (CHAT_MATRIX_FULL=1). Destructive/write/publish turns use partialOnMissingConfig when blocked.
#   RUN_ALL_CONCURRENT_SESSIONS=1 After the matrix, run parallel two-session ai/stream concurrency smoke.
#   CRAFTER_STUDIO_TOKEN or scripts/.studio-token — required unless RUN_ALL_SKIP_STUDIO=1.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/studio-auth.sh
source "${REPO_ROOT}/scripts/lib/studio-auth.sh"

RUN_ALL_REPORT_FILE="${RUN_ALL_REPORT_FILE:-${REPO_ROOT}/scripts/test/.run-all-report.jsonl}"
export RUN_ALL_REPORT_FILE
REPORT_CLI="${REPO_ROOT}/scripts/test/lib/run-report.mjs"

# Claude on Anthropic Tier 1: Sonnet allows ~30k input tokens/min — full tool matrix exceeds that in one turn.
if [[ "${CHAT_LLM:-}" =~ ^[Cc]laude$ ]]; then
  if [[ -z "${CHAT_SCENARIOS_FILE:-}" && "${CHAT_CLAUDE_FULL_MATRIX:-}" != "1" ]]; then
    export CHAT_SCENARIOS_FILE="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios-claude-smoke.json"
  fi
  if [[ "${CHAT_CLAUDE_FULL_MATRIX:-}" == "1" && -z "${CHAT_LLM_MODEL:-}" ]]; then
    export CHAT_LLM_MODEL=claude-opus-4-20250514
  fi
  if [[ "${CHAT_CLAUDE_FULL_MATRIX:-}" != "1" && "${RUN_ALL_SKIP_TOOL_RECIPE_MATRIX:-}" != "0" ]]; then
    export RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=1
  fi
fi

report_record() {
  local suite="$1" id="$2" status="$3"
  local label="${4:-}" reason="${5:-}" duration_ms="${6:-}"
  [[ -z "${RUN_ALL_REPORT_FILE}" ]] && return 0
  command -v node >/dev/null 2>&1 || return 0
  local args=(
    record
    "--file=${RUN_ALL_REPORT_FILE}"
    "--suite=${suite}"
    "--id=${id}"
    "--status=${status}"
  )
  [[ -n "${label}" ]] && args+=("--label=${label}")
  [[ -n "${reason}" ]] && args+=("--reason=${reason}")
  [[ -n "${duration_ms}" ]] && args+=("--duration-ms=${duration_ms}")
  node "${REPORT_CLI}" "${args[@]}" 2>/dev/null || true
}

report_run() {
  local suite="$1" id="$2" label="$3"
  shift 3
  local t0
  t0=$(date +%s%3N 2>/dev/null || echo 0)
  if "$@"; then
    local t1 dur=0
    t1=$(date +%s%3N 2>/dev/null || echo 0)
    if [[ "${t0}" != "0" && "${t1}" != "0" ]]; then dur=$((t1 - t0)); fi
    report_record "${suite}" "${id}" pass "${label}" "" "${dur}"
    return 0
  fi
  report_record "${suite}" "${id}" fail "${label}" "command exited non-zero"
  return 1
}

: > "${RUN_ALL_REPORT_FILE}"

_RUN_TOTAL=2
if [[ "${RUN_ALL_SKIP_STUDIO:-}" != "1" ]]; then
  _RUN_TOTAL=3
  if [[ "${RUN_ALL_SKIP_CHAT_SCENARIOS:-}" != "1" ]]; then
    _RUN_TOTAL=4
  fi
  if [[ "${RUN_ALL_SKIP_TOOL_RECIPE_MATRIX:-}" != "1" ]]; then
    _RUN_TOTAL=5
  fi
  if [[ "${RUN_ALL_CONCURRENT_SESSIONS:-}" == "1" ]]; then
    _RUN_TOTAL=6
  fi
fi

step() {
  echo ""
  echo "======== ${AI_TEST} $* ========"
}

fail() {
  echo "${AI_FAIL} run-all: $*" >&2
  exit 1
}

step "1/${_RUN_TOTAL}  bash -n (integration shell) + REST JSON selftest"
report_run "step1-offline" "bash-syntax-check" "bash -n on integration shell scripts" \
  bash -n "${REPO_ROOT}/scripts/studio-api.sh"
bash -n "${REPO_ROOT}/scripts/install-plugin.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/smoke.sh"
bash -n "${REPO_ROOT}/scripts/test/functional/rest-contracts.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/e2e-site-lifecycle.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/include/plugin-stream-probe.inc.sh"
bash -n "${REPO_ROOT}/scripts/test/integration/include/reporting.inc.sh"
bash -n "${REPO_ROOT}/scripts/lib/studio-auth.sh"
bash -n "${REPO_ROOT}/scripts/lib/emoji.inc.sh"
bash -n "${REPO_ROOT}/scripts/test/run-all.sh"
bash -n "${REPO_ROOT}/scripts/test/functional/run-tool-recipe-matrix.sh"
if command -v node >/dev/null 2>&1; then
  report_run "step1-offline" "node-check-run-chat-scenarios" "node --check run-chat-scenarios.mjs" \
    node --check "${REPO_ROOT}/scripts/test/functional/run-chat-scenarios.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/run-concurrent-chat-sessions.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/concurrent-ice-panel-storage.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/sse-chat-stream.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/generate-tool-recipe-scenarios.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/tool-id-parity.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/partial-failure.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/chat-llm-env.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/stylesheet-write-guard-parity.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/routing-correction-parity.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/sse-telemetry-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/sse-telemetry.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/router-json-parity.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/router-json-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/copy-field-plan-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/intent-execution-plan-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/serp-api-parse-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/functional/create-from-chat-draft-offline.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/copy-field-plan-parity.mjs"
  node --check "${REPO_ROOT}/scripts/test/lib/intent-execution-plan-parity.mjs"
  report_run "step1-offline" "tool-id-parity" "CORE_TOOLS vs UI tool id parity" \
    node "${REPO_ROOT}/scripts/test/functional/tool-id-parity.mjs"
  report_run "step1-offline" "stylesheet-write-guard-parity" "Stylesheet WriteContent structure guard" \
    node "${REPO_ROOT}/scripts/test/lib/stylesheet-write-guard-parity.mjs"
  report_run "step1-offline" "routing-correction-parity" "Presentation vs copy-modification routing guards" \
    node "${REPO_ROOT}/scripts/test/lib/routing-correction-parity.mjs"
  report_run "step1-offline" "recipe-catalog-offline" "Bundled intent recipe catalog JSON" \
    node "${REPO_ROOT}/scripts/test/functional/recipe-catalog-offline.mjs"
  report_run "step1-offline" "scenario-fixture-drift" "tool-recipe matrix fixture drift check" \
    node "${REPO_ROOT}/scripts/test/functional/generate-tool-recipe-scenarios.mjs" --check
  report_run "step1-offline" "concurrent-ice-panel-storage" "ICE panel localStorage isolation" \
    node "${REPO_ROOT}/scripts/test/functional/concurrent-ice-panel-storage.mjs"
  report_run "step1-offline" "sse-telemetry-offline" "SSE telemetry expect helpers (GenerateImage caps)" \
    node "${REPO_ROOT}/scripts/test/functional/sse-telemetry-offline.mjs"
  report_run "step1-offline" "router-json-offline" "Intent router JSON extract + parse parity" \
    node "${REPO_ROOT}/scripts/test/functional/router-json-offline.mjs"
  report_run "step1-offline" "copy-field-plan-offline" "Content field plan role parity" \
    node "${REPO_ROOT}/scripts/test/functional/copy-field-plan-offline.mjs"
  report_run "step1-offline" "intent-execution-plan-offline" "Intent execution plan tool chains" \
    node "${REPO_ROOT}/scripts/test/functional/intent-execution-plan-offline.mjs"
  report_run "step1-offline" "serp-api-parse-offline" "SerpApi result merge + failure messages" \
    node "${REPO_ROOT}/scripts/test/functional/serp-api-parse-offline.mjs"
  report_run "step1-offline" "create-from-chat-draft-offline" "Create-from-chat-draft prefetch parity" \
    node "${REPO_ROOT}/scripts/test/functional/create-from-chat-draft-offline.mjs"
fi
export REST_CONTRACTS_SELFTEST=1
"${REPO_ROOT}/scripts/test/functional/rest-contracts.sh"
unset REST_CONTRACTS_SELFTEST
echo "${AI_OK} OK"

step "2/${_RUN_TOTAL}  sources: yarn package (same gate as install-plugin.sh packaging)"
if ! command -v yarn >/dev/null 2>&1; then
  fail "yarn not on PATH (need Node toolchain for sources/)."
fi
(
  cd "${REPO_ROOT}/sources"
  if [[ "${RUN_ALL_WITH_LINT:-}" == "1" ]]; then
    yarn lint
  fi
  yarn package
)
report_record "step2-build" "yarn-package" pass "sources yarn package (+ form-pipeline verify)"
echo "${AI_OK} OK"

if [[ "${RUN_ALL_SKIP_STUDIO:-}" == "1" ]]; then
  step "3/${_RUN_TOTAL}  Studio plugin REST contracts (skipped RUN_ALL_SKIP_STUDIO=1)"
  echo "${AI_SKIP} OK (skipped Studio step; chat scenarios need live Studio)"
else
  step "3/${_RUN_TOTAL}  Studio plugin REST contracts (functional/rest-contracts.sh)"
  if ! studio_require_token; then
    fail "No JWT for Studio checks. Set CRAFTER_STUDIO_TOKEN or scripts/.studio-token, or re-run with RUN_ALL_SKIP_STUDIO=1."
  fi
  CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-}" INTEGRATION_SITE_ID="${INTEGRATION_SITE_ID:-}" \
    "${SCRIPT_DIR}/functional/rest-contracts.sh"
  echo "${AI_OK} OK"

  if [[ "${RUN_ALL_SKIP_CHAT_SCENARIOS:-}" == "1" ]]; then
    step "4/${_RUN_TOTAL}  Plugin chat scenarios (skipped RUN_ALL_SKIP_CHAT_SCENARIOS=1)"
    echo "${AI_SKIP} OK (skipped chat scenarios)"
  else
    step "4/${_RUN_TOTAL}  Plugin chat scenarios (functional/run-chat-scenarios.mjs)"
    if ! command -v node >/dev/null 2>&1; then
      fail "node on PATH is required for chat scenarios (install Node 18+ or skip with RUN_ALL_SKIP_CHAT_SCENARIOS=1)."
    fi
    export CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
    export CHAT_SITE_ID="${CHAT_SITE_ID:-${INTEGRATION_SITE_ID:-}}"
    unset CHAT_SCENARIO_GROUP
    scen="${CHAT_SCENARIOS_FILE:-}"
    if [[ -z "${scen}" ]]; then
      if [[ -f "${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.json" ]]; then
        scen="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.json"
      elif [[ "${CHAT_LLM:-}" =~ ^[Cc]laude$ && "${CHAT_CLAUDE_FULL_MATRIX:-}" != "1" && -f "${REPO_ROOT}/scripts/test/scenarios/chat-scenarios-claude-smoke.json" ]]; then
        scen="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios-claude-smoke.json"
      else
        scen="${REPO_ROOT}/scripts/test/scenarios/chat-scenarios.example.json"
      fi
    fi
    if [[ "${CHAT_LLM:-}" =~ ^[Cc]laude$ && "${CHAT_CLAUDE_FULL_MATRIX:-}" != "1" ]]; then
      echo "ℹ️  Claude smoke profile (Tier-1 Sonnet TPM): ${scen##*/} — set CHAT_CLAUDE_FULL_MATRIX=1 + CHAT_LLM_MODEL=claude-opus-4-20250514 for full matrix."
    elif [[ "${CHAT_LLM:-}" =~ ^[Cc]laude$ ]]; then
      echo "ℹ️  Claude full matrix: ${scen##*/}  model=${CHAT_LLM_MODEL:-server default}"
    fi
    export RUN_ALL_REPORT_SUITE="step4-chat-scenarios"
    if ! node "${REPO_ROOT}/scripts/test/functional/run-chat-scenarios.mjs" "${scen}"; then
      if [[ "${RUN_ALL_CONTINUE_ON_FAIL:-}" == "1" ]]; then
        echo "${AI_WARN} chat scenarios had failures (RUN_ALL_CONTINUE_ON_FAIL=1 — continuing)"
      else
        exit 1
      fi
    fi
    echo "${AI_OK} OK"
  fi

  if [[ "${RUN_ALL_SKIP_TOOL_RECIPE_MATRIX:-}" == "1" ]]; then
    step "5/${_RUN_TOTAL}  Tool + intent-recipe matrix (skipped RUN_ALL_SKIP_TOOL_RECIPE_MATRIX=1)"
    echo "${AI_SKIP} OK (skipped tool/recipe matrix)"
  else
    step "5/${_RUN_TOTAL}  Tool + intent-recipe matrix (functional/run-tool-recipe-matrix.sh)"
    if ! command -v node >/dev/null 2>&1; then
      fail "node on PATH is required for tool/recipe matrix."
    fi
    export CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
    export CHAT_SITE_ID="${CHAT_SITE_ID:-${INTEGRATION_SITE_ID:-}}"
    export CHAT_SKIP_OPTIONAL=0
    export CHAT_MATRIX_FULL=1
    export CHAT_MATRIX_ALLOW_WRITES=1
    export CHAT_MATRIX_ALLOW_PUBLISH=1
    RUN_TOOL_RECIPE_MATRIX_OFFLINE_ONLY=0 "${SCRIPT_DIR}/functional/run-tool-recipe-matrix.sh" || {
      if [[ "${RUN_ALL_CONTINUE_ON_FAIL:-}" == "1" ]]; then
        echo "${AI_WARN} tool/recipe matrix had failures (RUN_ALL_CONTINUE_ON_FAIL=1 — continuing)"
      else
        exit 1
      fi
    }
    echo "${AI_OK} OK"
  fi

  if [[ "${RUN_ALL_CONCURRENT_SESSIONS:-}" == "1" ]]; then
    step "6/${_RUN_TOTAL}  Concurrent chat sessions (functional/run-concurrent-chat-sessions.mjs)"
    if ! command -v node >/dev/null 2>&1; then
      fail "node on PATH is required for concurrent session tests."
    fi
    export CRAFTER_STUDIO_URL="${CRAFTER_STUDIO_URL:-http://localhost:8080}"
    export CHAT_SITE_ID="${CHAT_SITE_ID:-${INTEGRATION_SITE_ID:-}}"
    export RUN_ALL_REPORT_SUITE="step6-concurrent-sessions"
    node "${REPO_ROOT}/scripts/test/functional/run-concurrent-chat-sessions.mjs" || {
      if [[ "${RUN_ALL_CONTINUE_ON_FAIL:-}" == "1" ]]; then
        echo "${AI_WARN} concurrent sessions had failures (RUN_ALL_CONTINUE_ON_FAIL=1 — continuing)"
      else
        exit 1
      fi
    }
    echo "${AI_OK} OK"
  fi
fi

echo ""
echo "======== ${AI_OK} run-all: finished ========"
if command -v node >/dev/null 2>&1 && [[ -f "${RUN_ALL_REPORT_FILE}" ]]; then
  node "${REPORT_CLI}" print --file="${RUN_ALL_REPORT_FILE}" --title="run-all: complete test report"
fi
