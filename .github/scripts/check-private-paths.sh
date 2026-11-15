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

usage() {
  printf 'usage: %s [staged | history | range <rev-list arguments>]\n' "$0" >&2
  exit "${USAGE_ERROR}"
}

mode="${1:-history}"

# Each flag closes a way past --diff-filter=A: --no-renames, a move into _doc/ listed as a rename;
# -m, a merge commit that adds a file itself (merges list no files by default); --root, the first
# commit, which log.showRoot=false would hide.
case "${mode}" in
  staged)
    candidates="$(git diff --cached --name-only --diff-filter=ACMR)"
    ;;
  history)
    # A file committed and removed again leaves a clean tip and stays in public history all the same.
    candidates="$(git log --all --format= --name-only --no-renames -m --root --diff-filter=A | sort -u)"
    ;;
  range)
    if [ "$#" -lt 2 ]; then
      usage
    fi
    shift
    candidates="$(git log --format= --name-only --no-renames -m --root --diff-filter=A "$@" | sort -u)"
    ;;
  *)
    usage
    ;;
esac

set +e
violations="$(printf '%s\n' "${candidates}" | grep -E "${PRIVATE_PATH_PATTERN}")"
grep_status=$?
set -e

if [ "${grep_status}" -eq "${GREP_NO_MATCH_STATUS}" ]; then
  printf 'OK    no private path is tracked (%s)\n' "${mode}"
  exit 0
fi
if [ "${grep_status}" -ne 0 ]; then
  printf 'ERROR grep failed with status %s\n' "${grep_status}" >&2
  exit "${grep_status}"
fi

printf 'ERROR private paths must never be committed:\n%s\n' "${violations}" >&2
exit 1
