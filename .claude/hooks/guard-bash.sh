#!/usr/bin/env bash
#
# Claude Code PreToolUse hook for Bash: refuses commands that this repository forbids outright.
# Reads the hook's JSON on stdin; exit 2 blocks the command and returns stderr to Claude.

set -euo pipefail

readonly BLOCK=2

refuse() {
  local reason="$1"

  printf 'Blocked by .claude/hooks/guard-bash.sh: %s\n' "${reason}" >&2
  exit "${BLOCK}"
}

read_command_text() {
  python3 -c 'import json, sys; print(json.load(sys.stdin).get("tool_input", {}).get("command", ""))'
}

current_branch() {
  local branch_name

  # Outside a repository there is no branch: the push is judged by its text alone.
  if ! branch_name="$(git -C "${CLAUDE_PROJECT_DIR:-.}" rev-parse --abbrev-ref HEAD 2>/dev/null)"; then
    return 0
  fi

  printf '%s\n' "${branch_name}"
}

refuse_sweeping_add() {
  local command_text="$1"

  case "${command_text}" in
    *"git add -A"* | *"git add --all"* | *"git add ." | *"git add . "* | *"git add -u"*)
      refuse "stage explicit paths only; _doc/ and local agent files must never be swept in"
      ;;
  esac
}

refuse_hook_bypass() {
  local command_text="$1"

  case "${command_text}" in
    *"--no-verify"*)
      refuse "hooks are part of the commit gate; do not bypass them"
      ;;
  esac
}

refuse_force_push_to_main() {
  local command_text="$1"
  local branch_name

  case "${command_text}" in
    *"git push"*"--force-with-lease"*) ;;
    *) return 0 ;;
  esac

  case "${command_text}" in
    *" main"* | *":main"* | *"/main"*)
      refuse "main is never force-pushed"
      ;;
  esac

  branch_name="$(current_branch)"

  if [ "${branch_name}" = "main" ]; then
    refuse "main is never force-pushed"
  fi
}

refuse_plain_force_push() {
  local command_text="$1"

  case "${command_text}" in
    # " --force" also matches --force-with-lease, which refuse_force_push_to_main judges instead.
    *"git push"*"--force-with-lease"*)
      return 0
      ;;
    *"git push"*" --force"* | *"git push"*" -f"* | *"git push"*" +"*)
      refuse "plain force pushes are not used; a rebased feature branch takes --force-with-lease"
      ;;
  esac
}

refuse_merge() {
  local command_text="$1"

  case "${command_text}" in
    *"git merge "* | *"git pull --no-rebase"*) ;;
    *) return 0 ;;
  esac

  case "${command_text}" in
    *"--ff-only"*) return 0 ;;
  esac

  refuse "history is linear: rebase onto main (git rebase origin/main) instead of merging"
}

main() {
  local command_text

  if ! command_text="$(read_command_text)"; then
    refuse "the hook input could not be read, so the command cannot be checked"
  fi

  refuse_sweeping_add "${command_text}"
  refuse_hook_bypass "${command_text}"
  refuse_force_push_to_main "${command_text}"
  refuse_plain_force_push "${command_text}"
  refuse_merge "${command_text}"
}

if ! main "$@"; then
  exit 1
fi
