#!/usr/bin/env bash
#
# Fails when a private path is tracked, is staged for the next commit, or is added by commits about
# to be pushed.
#
# Usage:
#   check-private-paths.sh                       # every path in history (Guards workflow)
#   check-private-paths.sh staged                # staged files only (pre-commit hook, commit gate)
#   check-private-paths.sh range <rev-list args> # files the given commits add (pre-push hook)
#
# The private side of this repository is _doc/ and the maintainer-local agent files. .gitignore is
# the first guard; this script is the backstop for a forced add.

set -euo pipefail

readonly PRIVATE_PATH_PATTERN='^"?(_doc/|CLAUDE\.local\.md$|\.claude/settings\.local\.json$)'
readonly GREP_NO_MATCH_STATUS=1
readonly USAGE_ERROR=2

log_ok() {
  printf 'OK    %s\n' "$1"
}

log_error() {
  printf 'ERROR %s\n' "$1" >&2
}

indented_to_stderr() {
  local listed_lines="$1"
  local listed_line

  while IFS= read -r listed_line; do
    printf '  %s\n' "${listed_line}" >&2
  done <<< "${listed_lines}"
}

usage_error() {
  log_error "usage: $0 [staged | history | range <rev-list arguments>]"
  exit "${USAGE_ERROR}"
}

staged_paths() {
  if ! git diff --cached --name-only --diff-filter=ACMR; then
    log_error "could not list the staged files"
    return 1
  fi
}

# Each flag closes a way past --diff-filter=A: --no-renames, a move into _doc/ listed as a rename;
# -m, a merge commit that adds a file itself (merges list no files by default); --root, the first
# commit, which log.showRoot=false would hide.
added_paths() {
  local added_path_lines

  if ! added_path_lines="$(git log --format= --name-only --no-renames -m --root --diff-filter=A "$@")"; then
    log_error "could not list the files added by: git log $*"
    return 1
  fi

  printf '%s\n' "${added_path_lines}" | sort -u
}

# A file committed and removed again leaves a clean tip and stays in public history all the same.
history_paths() {
  added_paths --all
}

range_paths() {
  added_paths "$@"
}

private_paths_among() {
  local candidate_paths="$1"
  local private_paths
  local grep_status=0

  private_paths="$(printf '%s\n' "${candidate_paths}" | grep -E "${PRIVATE_PATH_PATTERN}")" || grep_status=$?

  if [ "${grep_status}" -eq "${GREP_NO_MATCH_STATUS}" ]; then
    return 0
  fi

  if [ "${grep_status}" -ne 0 ]; then
    log_error "matching the private path pattern failed (grep exit ${grep_status})"
    return 1
  fi

  printf '%s\n' "${private_paths}"
}

candidate_paths_for() {
  local mode="$1"
  shift

  case "${mode}" in
    staged)
      staged_paths
      ;;
    history)
      history_paths
      ;;
    range)
      range_paths "$@"
      ;;
  esac
}

main() {
  local mode="${1:-history}"
  local candidate_paths
  local violations

  case "${mode}" in
    staged | history)
      ;;
    range)
      if [ "$#" -lt 2 ]; then
        usage_error
      fi
      ;;
    *)
      usage_error
      ;;
  esac

  if [ "$#" -gt 0 ]; then
    shift
  fi

  if ! candidate_paths="$(candidate_paths_for "${mode}" "$@")"; then
    return 1
  fi

  if ! violations="$(private_paths_among "${candidate_paths}")"; then
    return 1
  fi

  if [ -n "${violations}" ]; then
    log_error "private paths must never be committed:"
    indented_to_stderr "${violations}"
    return 1
  fi

  log_ok "no private path is tracked (${mode})"
}

if ! main "$@"; then
  exit 1
fi
