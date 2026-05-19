#!/usr/bin/env bash
# shellcheck shell=bash
# Optional CodeRabbit CLI review (run manually from repo root).
# Requires emoji.inc.sh: source scripts/lib/emoji.inc.sh (or studio-auth.sh) for AI_SKIP, AI_TEST, AI_FAIL.

# Returns 0 when any skip env is set.
_coderabbit_review_skip() {
  [[ "${SKIP_CODERABBIT:-}" == "1" ]] && return 0
  [[ "${RUN_ALL_SKIP_CODERABBIT:-}" == "1" ]] && return 0
  return 1
}

# Returns 0 when review must run and run-all should fail if CLI is missing.
_coderabbit_review_required() {
  [[ "${WITH_CODERABBIT:-}" == "1" ]] && return 0
  [[ "${RUN_ALL_WITH_CODERABBIT:-}" == "1" ]] && return 0
  return 1
}

# Plugin source only (not Rollup bundles under authoring/static-assets, not shell/CI).
_coderabbit_default_review_dirs() {
  echo "sources authoring/scripts"
}

# True when path has staged or unstaged diff vs HEAD.
_coderabbit_dir_has_changes() {
  local repo_root="$1"
  local rel="$2"
  if ! git -C "${repo_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    return 0
  fi
  ! git -C "${repo_root}" diff --quiet -- "${rel}" 2>/dev/null ||
    ! git -C "${repo_root}" diff --cached --quiet -- "${rel}" 2>/dev/null
}

# Run `coderabbit review --type uncommitted` (plain text; working-tree diffs under source dirs only).
# Env: SKIP_CODERABBIT, RUN_ALL_SKIP_CODERABBIT — skip when "1".
#      WITH_CODERABBIT, RUN_ALL_WITH_CODERABBIT — require CLI; non-zero on review failure.
#      CODERABBIT_REVIEW_TYPE — uncommitted (default), committed, or all.
#      CODERABBIT_REVIEW_DIRS — override default dirs (default: sources authoring/scripts).
#      CODERABBIT_REVIEW_FULL=1 — entire repo uncommitted diff (includes generated bundles; slow).
#      CODERABBIT_REVIEW_AGENT=1 — structured JSON (--agent) instead of plain text.
coderabbit_review_uncommitted() {
  local repo_root="${1:?repo root required}"
  local review_type="${CODERABBIT_REVIEW_TYPE:-uncommitted}"
  local agent_flag=()
  if [[ "${CODERABBIT_REVIEW_AGENT:-}" == "1" ]]; then
    agent_flag=(--agent)
  fi
  if _coderabbit_review_skip; then
    echo "${AI_SKIP} CodeRabbit review (skip env set)"
    return 0
  fi
  local cr_cmd=""
  if command -v coderabbit >/dev/null 2>&1; then
    cr_cmd="coderabbit"
  elif command -v cr >/dev/null 2>&1; then
    cr_cmd="cr"
  fi
  if [[ -z "${cr_cmd}" ]]; then
    if _coderabbit_review_required; then
      echo "${AI_FAIL} CodeRabbit CLI not on PATH (install: curl -fsSL https://cli.coderabbit.ai/install.sh | sh)" >&2
      return 1
    fi
    echo "${AI_SKIP} CodeRabbit CLI not on PATH (skipped review)"
    return 0
  fi
  if [[ "${CODERABBIT_REVIEW_FULL:-}" == "1" ]]; then
    echo "${AI_TEST} ${cr_cmd} review --type ${review_type} (full repo: ${repo_root})"
    (cd "${repo_root}" && "${cr_cmd}" review "${agent_flag[@]}" --type "${review_type}")
    return $?
  fi
  local dirs_raw="${CODERABBIT_REVIEW_DIRS:-$(_coderabbit_default_review_dirs)}"
  local reviewed=0
  local dir
  for dir in ${dirs_raw}; do
    if ! _coderabbit_dir_has_changes "${repo_root}" "${dir}"; then
      continue
    fi
    reviewed=1
    echo "${AI_TEST} ${cr_cmd} review --type ${review_type} --dir ${dir} (${repo_root})"
    (cd "${repo_root}" && "${cr_cmd}" review "${agent_flag[@]}" --type "${review_type}" --dir "${dir}") || return $?
  done
  if [[ "${reviewed}" -eq 0 ]]; then
    echo "${AI_SKIP} CodeRabbit: no uncommitted changes under source dirs (${dirs_raw})"
  fi
  return 0
}

# Back-compat alias (older call sites).
coderabbit_review_agent() {
  coderabbit_review_uncommitted "$@"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -euo pipefail
  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
  # shellcheck source=lib/emoji.inc.sh
  source "${SCRIPT_DIR}/lib/emoji.inc.sh"
  coderabbit_review_uncommitted "${REPO_ROOT}"
fi
