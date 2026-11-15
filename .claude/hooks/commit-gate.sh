#!/usr/bin/env bash
#
# Claude Code PreToolUse hook, run before `git commit`: the same checks as the pre-commit hook, so
# an agent cannot commit what a human's hook would refuse. It also requires the repository's hooks
# to be enabled, because the commit message only exists once git runs the commit-msg hook.
# Exit 2 blocks the commit and returns stderr to Claude.

set -euo pipefail

readonly BLOCK=2
readonly HOOKS_PATH='.githooks'

repository_root="$(git rev-parse --show-toplevel)"
readonly repository_root

configured_hooks_path="$(git config --get core.hooksPath || true)" # unset is reported just below
if [ "${configured_hooks_path}" != "${HOOKS_PATH}" ]; then
  printf 'ERROR repository hooks are not enabled, so the commit message would go unchecked.\n' >&2
  printf '      Run: git config core.hooksPath %s\n' "${HOOKS_PATH}" >&2
  exit "${BLOCK}"
fi

if ! "${repository_root}/.github/scripts/check-private-paths.sh" staged >&2; then
  exit "${BLOCK}"
fi
if ! "${repository_root}/.github/scripts/check-emails.sh" staged >&2; then
  exit "${BLOCK}"
fi

staged_scripts="$(git diff --cached --name-only --diff-filter=ACMR -- '*.sh' '.githooks/*')"
if [ -n "${staged_scripts}" ] && command -v shellcheck > /dev/null 2>&1; then
  # Word splitting is wanted: one path per word, and no path here contains a space.
  # shellcheck disable=SC2086
  if ! shellcheck -S style ${staged_scripts} >&2; then
    exit "${BLOCK}"
  fi
fi

exit 0
