#!/usr/bin/env bash
#
# Claude Code PreToolUse hook for Bash: refuses commands that this repository forbids outright.
# Reads the hook's JSON on stdin; exit 2 blocks the command and returns stderr to Claude.

set -euo pipefail

readonly BLOCK=2

command_text="$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("tool_input", {}).get("command", ""))')"

refuse() {
  printf 'Blocked by .claude/hooks/guard-bash.sh: %s\n' "$1" >&2
  exit "${BLOCK}"
}

case "${command_text}" in
  *"git add -A"* | *"git add --all"* | *"git add ." | *"git add . "* | *"git add -u"*)
    refuse "stage explicit paths only; _doc/ and local agent files must never be swept in"
    ;;
  *"--no-verify"*)
    refuse "hooks are part of the commit gate; do not bypass them"
    ;;
  # Must precede the plain-force pattern below, which would also match it.
  *"git push"*"--force-with-lease"*)
    current_branch="$(git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --abbrev-ref HEAD 2>/dev/null || true)" # outside a repository: judged by the text alone
    case "${command_text}" in
      *" main"* | *":main"* | *"/main"*) refuse "main is never force-pushed" ;;
    esac
    if [ "${current_branch}" = "main" ]; then
      refuse "main is never force-pushed"
    fi
    ;;
  *"git push"*" --force"* | *"git push"*" -f"* | *"git push"*" +"*)
    refuse "plain force pushes are not used; a rebased feature branch takes --force-with-lease"
    ;;
  *"git merge "* | *"git pull --no-rebase"*)
    case "${command_text}" in
      *"--ff-only"*) ;;
      *) refuse "history is linear: rebase onto main (git rebase origin/main) instead of merging" ;;
    esac
    ;;
esac

exit 0
