#!/usr/bin/env bash
#
# Refuses a commit message that names an AI assistant or carries its attribution, and refuses merge
# commits: history reaches main by rebase merge, so every commit lands on main as written.
#
# Usage:
#   check-commit-messages.sh file <path>   # one message file (the commit-msg hook)
#   check-commit-messages.sh ci            # the commits a push or pull request adds (Guards)

set -euo pipefail

readonly USAGE_ERROR=2
readonly NULL_SHA='0000000000000000000000000000000000000000'
# Matched case-insensitively after AGENT_FILE_PATTERN is removed, so a message may still say that it
# changed CLAUDE.md or a file under .claude/. The robot is the attribution line's emoji.
readonly ATTRIBUTION_PATTERN='claude|anthropic|ai[- ](generated|assisted)|🤖'
readonly AGENT_FILE_PATTERN='CLAUDE(\.local)?\.md|\.claude/?'

log_ok() {
  printf 'OK    %s\n' "$1"
}

log_warn() {
  printf 'WARN  %s\n' "$1" >&2
}

log_error() {
  printf 'ERROR %s\n' "$1" >&2
}

# Prints the offending lines, numbered; prints nothing when the message is clean.
attribution_lines() {
  local message_text="$1"
  local without_agent_files
  local matches
  local grep_status=0

  without_agent_files="$(printf '%s\n' "${message_text}" | LC_ALL=C sed -E "s#${AGENT_FILE_PATTERN}##g")"

  matches="$(printf '%s\n' "${without_agent_files}" | LC_ALL=C grep -n -i -E "${ATTRIBUTION_PATTERN}")" || grep_status=$?
  if [ "${grep_status}" -gt 1 ]; then
    log_error "attribution pattern failed to run (grep exit ${grep_status})"
    return 1
  fi

  printf '%s' "${matches}"
}

check_message() {
  local label="$1"
  local message_text="$2"
  local offending

  if ! offending="$(attribution_lines "${message_text}")"; then
    return 1
  fi
  if [ -n "${offending}" ]; then
    log_error "${label}: a commit message carries the change only, never an assistant's name or attribution"
    printf '  %s\n' "${offending}" >&2
    return 1
  fi
  return 0
}

check_file() {
  local message_path="$1"
  local message_text

  if [ ! -f "${message_path}" ]; then
    log_error "commit message file not found: ${message_path}"
    return 1
  fi

  # The hook sees the message before git strips its comment lines; strip them the same way.
  if ! message_text="$(git stripspace --strip-comments < "${message_path}")"; then
    log_error "could not read the commit message in ${message_path}"
    return 1
  fi
  if [ -z "${message_text}" ]; then
    return 0
  fi

  if ! check_message "commit message" "${message_text}"; then
    return 1
  fi
  log_ok "commit message names no assistant"
}

# Prints the rev-list arguments selecting what this event adds to the repository.
ci_range() {
  local event_name="${GITHUB_EVENT_NAME:-}"

  if [ "${event_name}" = "pull_request" ]; then
    printf '%s..%s\n' "${PR_BASE_SHA:?PR_BASE_SHA is required for a pull request}" "${PR_HEAD_SHA:?PR_HEAD_SHA is required for a pull request}"
    return 0
  fi

  local pushed_sha="${GITHUB_SHA:?GITHUB_SHA is required}"
  local before_sha="${PUSH_BEFORE_SHA:-${NULL_SHA}}"

  if [ "${before_sha}" != "${NULL_SHA}" ] && git cat-file -e "${before_sha}^{commit}" 2>/dev/null; then
    printf '%s..%s\n' "${before_sha}" "${pushed_sha}"
    return 0
  fi
  if git rev-parse -q --verify origin/main > /dev/null; then
    printf 'origin/main..%s\n' "${pushed_sha}"
    return 0
  fi
  # The first push to an empty repository: everything is new.
  printf '%s\n' "${pushed_sha}"
}

check_ci() {
  local range
  local commits
  local merges
  local failures=0
  local commit_sha
  local message_text

  if ! range="$(ci_range)"; then
    return 1
  fi
  if ! commits="$(git rev-list "${range}")"; then
    log_error "could not list the commits in ${range}"
    return 1
  fi
  if [ -z "${commits}" ]; then
    if [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ]; then
      log_error "pull request range ${range} holds no commits"
      return 1
    fi
    log_warn "no new commits in ${range}; nothing was examined"
    return 0
  fi

  if ! merges="$(git rev-list --merges "${range}")"; then
    log_error "could not list the merge commits in ${range}"
    return 1
  fi
  if [ -n "${merges}" ]; then
    log_error "merge commits in ${range}; rebase onto main instead of merging it in:"
    printf '%s\n' "${merges}" | sed 's/^/  /' >&2
    failures=$((failures + 1))
  fi

  while IFS= read -r commit_sha; do
    if ! message_text="$(git log -1 --format=%B "${commit_sha}")"; then
      log_error "could not read the message of ${commit_sha}"
      return 1
    fi
    if ! check_message "commit ${commit_sha}" "${message_text}"; then
      failures=$((failures + 1))
    fi
  done <<< "${commits}"

  if [ "${failures}" -gt 0 ]; then
    return 1
  fi
  log_ok "$(printf '%s\n' "${commits}" | wc -l | tr -d ' ') commit(s) in ${range}: no merge commit, no assistant attribution"
}

main() {
  local mode="${1:-}"

  case "${mode}" in
    file)
      if [ "$#" -ne 2 ]; then
        log_error "usage: $0 file <path>"
        exit "${USAGE_ERROR}"
      fi
      check_file "$2"
      ;;
    ci)
      check_ci
      ;;
    *)
      log_error "usage: $0 file <path> | ci"
      exit "${USAGE_ERROR}"
      ;;
  esac
}

if ! main "$@"; then
  exit 1
fi
