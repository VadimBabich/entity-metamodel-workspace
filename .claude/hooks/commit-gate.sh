#!/usr/bin/env bash
#
# Claude Code PreToolUse hook, run before `git commit`: the same checks as the pre-commit hook, so
# an agent cannot commit what a human's hook would refuse. It also requires the repository's hooks
# to be enabled, because the commit message only exists once git runs the commit-msg hook.
# Exit 2 blocks the commit and returns stderr to Claude.

set -euo pipefail

readonly BLOCK=2
readonly HOOKS_PATH='.githooks'
readonly GIT_CONFIG_KEY_UNSET=1

log_error() {
  printf 'ERROR %s\n' "$1" >&2
}

log_warn() {
  printf 'WARN  %s\n' "$1" >&2
}

block() {
  exit "${BLOCK}"
}

repository_root() {
  git rev-parse --show-toplevel
}

configured_hooks_path() {
  local hooks_path
  local config_status=0

  hooks_path="$(git config --get core.hooksPath)" || config_status=$?

  if [ "${config_status}" -eq "${GIT_CONFIG_KEY_UNSET}" ]; then
    return 0
  fi

  if [ "${config_status}" -ne 0 ]; then
    return "${config_status}"
  fi

  printf '%s\n' "${hooks_path}"
}

require_repository_hooks() {
  local hooks_path

  if ! hooks_path="$(configured_hooks_path)"; then
    log_error "core.hooksPath could not be read; check the repository's git configuration."
    block
  fi

  if [ "${hooks_path}" != "${HOOKS_PATH}" ]; then
    log_error "repository hooks are not enabled, so the commit message would go unchecked."
    printf '      Run: git config core.hooksPath %s\n' "${HOOKS_PATH}" >&2
    block
  fi
}

require_guard_passes() {
  local guard_script="$1"

  if ! "${guard_script}" staged >&2; then
    block
  fi
}

shellcheck_staged_scripts() {
  local staged_scripts
  local staged_script_paths=()
  local script_path

  if ! staged_scripts="$(git diff --cached --name-only --diff-filter=ACMR -- '*.sh' '.githooks/*')"; then
    log_error "the staged scripts could not be listed, so they cannot be checked."
    block
  fi

  if [ -z "${staged_scripts}" ]; then
    return 0
  fi

  if ! command -v shellcheck >/dev/null 2>&1; then
    log_warn "shellcheck is not installed; the staged scripts go unchecked here and only CI checks them."
    return 0
  fi

  while IFS= read -r script_path; do
    staged_script_paths+=("${script_path}")
  done <<< "${staged_scripts}"

  if ! shellcheck -S style "${staged_script_paths[@]}" >&2; then
    block
  fi
}

main() {
  local root

  if ! root="$(repository_root)"; then
    log_error "git could not open the repository (see git's message above); the commit cannot be checked."
    block
  fi

  require_repository_hooks
  require_guard_passes "${root}/.github/scripts/check-private-paths.sh"
  require_guard_passes "${root}/.github/scripts/check-emails.sh"
  shellcheck_staged_scripts
}

if ! main "$@"; then
  exit "${BLOCK}"
fi
