#!/usr/bin/env bash
#
# Fails on an email address that is not in .github/allowed-emails.txt.
#
# Usage:
#   check-emails.sh tree      # tracked files, and the author and committer of every commit (Guards)
#   check-emails.sh staged    # staged file contents and the identity the commit will record
#                             # (pre-commit, commit gate)

set -euo pipefail

readonly EMAIL_PATTERN='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
readonly USAGE_ERROR=2

repository_root="$(git rev-parse --show-toplevel)"
readonly repository_root
readonly ALLOWLIST="${repository_root}/.github/allowed-emails.txt"

mode="${1:-}"

collect_from_files() {
  local list_command=("$@")
  local found

  set +e
  found="$("${list_command[@]}" -z | xargs -0 grep -I -h -o -E "${EMAIL_PATTERN}" 2>/dev/null)"
  set -e
  printf '%s\n' "${found}"
}

# The author and committer addresses git will record. Not user.email alone: with none configured, git
# synthesises user@hostname, and only Guards would see it, after the push.
identity_addresses() {
  local ident_variable
  local ident

  for ident_variable in GIT_AUTHOR_IDENT GIT_COMMITTER_IDENT; do
    if ! ident="$(git var "${ident_variable}" 2>/dev/null)"; then
      printf 'ERROR no commit identity (%s): set user.name and user.email for this clone\n' "${ident_variable}" >&2
      return 1
    fi
    printf '%s\n' "${ident}" | sed -E 's/^.*<([^>]*)>.*$/\1/'
  done
}

case "${mode}" in
  tree)
    addresses="$(collect_from_files git ls-files)"
    addresses+=$'\n'"$(git log --all --format='%ae%n%ce' 2>/dev/null || true)"
    ;;
  staged)
    addresses="$(collect_from_files git diff --cached --name-only --diff-filter=ACMR)"
    if ! identity="$(identity_addresses)"; then
      exit 1
    fi
    addresses+=$'\n'"${identity}"
    ;;
  *)
    printf 'usage: %s tree|staged\n' "$0" >&2
    exit "${USAGE_ERROR}"
    ;;
esac

if [ ! -f "${ALLOWLIST}" ]; then
  printf 'ERROR allowlist not found: %s\n' "${ALLOWLIST}" >&2
  exit 1
fi

# A read loop rather than mapfile: macOS ships bash 3.2.
patterns=()
while IFS= read -r pattern; do
  patterns+=("${pattern}")
done < <(grep -v -E '^[[:space:]]*(#|$)' "${ALLOWLIST}")

unlisted=()
while IFS= read -r address; do
  [ -n "${address}" ] || continue
  allowed=false
  for pattern in "${patterns[@]}"; do
    # shellcheck disable=SC2053 # the right-hand side is a glob on purpose
    if [[ "${address}" == ${pattern} ]]; then
      allowed=true
      break
    fi
  done
  if [ "${allowed}" = false ]; then
    unlisted+=("${address}")
  fi
done < <(printf '%s\n' "${addresses}" | sort -u)

if [ "${#unlisted[@]}" -eq 0 ]; then
  printf 'OK    no unlisted email address (%s)\n' "${mode}"
  exit 0
fi

printf 'ERROR email addresses not in %s:\n' ".github/allowed-emails.txt" >&2
printf '  %s\n' "${unlisted[@]}" >&2
exit 1
