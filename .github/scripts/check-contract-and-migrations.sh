#!/usr/bin/env bash
#
# Refuses a change that edits an applied migration, or that changes the API contract without raising
# its version. Flyway validates checksums only on a database that already applied a file, and every CI
# database is new, so nothing else would notice an edited migration before a long-lived environment.
#
# Usage:
#   check-contract-and-migrations.sh migrations  [<base> <head>]
#   check-contract-and-migrations.sh api-version [<base> <head>]
# Without <base> and <head> the range is the one the GitHub event adds (Guards).

set -euo pipefail

readonly USAGE_ERROR=2
readonly NULL_SHA='0000000000000000000000000000000000000000'
readonly MIGRATION_DIR='workspace-app/src/main/resources/db/migration'
readonly VERSIONED_MIGRATION_PATTERN='^V[0-9]+([._][0-9]+)*__.+\.sql$'
readonly CONTRACT_FILE='workspace-contract/src/main/openapi/workspace.yaml'
readonly SEMANTIC_VERSION_PATTERN='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$'

log_ok() {
  printf 'OK    %s\n' "$1"
}

log_warn() {
  printf 'WARN  %s\n' "$1" >&2
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
  log_error "usage: $0 migrations|api-version [<base> <head>]"
  exit "${USAGE_ERROR}"
}

require_variable() {
  local variable_name="$1"
  local purpose="$2"

  if [ -z "${!variable_name:-}" ]; then
    log_error "${variable_name} is required ${purpose}"
    return 1
  fi
}

resolve_commit() {
  local reference="$1"

  if ! git rev-parse -q --verify "${reference}^{commit}"; then
    log_error "'${reference}' names no commit; fetch the full history (fetch-depth: 0) or pass an existing ref"
    return 1
  fi
}

ci_base_reference() {
  local before_sha="${PUSH_BEFORE_SHA:-${NULL_SHA}}"

  if [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ]; then
    if ! require_variable PR_BASE_SHA "for a pull request"; then
      return 1
    fi

    printf '%s\n' "${PR_BASE_SHA}"
    return 0
  fi

  if [ "${GITHUB_REF:-}" != "refs/heads/main" ]; then
    printf 'origin/main\n'
    return 0
  fi

  # The first push to main has no earlier commit: an empty base tells run_check to compare nothing.
  if [ "${before_sha}" = "${NULL_SHA}" ]; then
    return 0
  fi

  printf '%s\n' "${before_sha}"
}

ci_head_reference() {
  if [ "${GITHUB_EVENT_NAME:-}" = "pull_request" ]; then
    if ! require_variable PR_HEAD_SHA "for a pull request"; then
      return 1
    fi

    printf '%s\n' "${PR_HEAD_SHA}"
    return 0
  fi

  if ! require_variable GITHUB_SHA "for a push"; then
    return 1
  fi

  printf '%s\n' "${GITHUB_SHA}"
}

base_commit() {
  local base_reference="$1"
  local head_sha="$2"
  local base_sha
  local merge_base_sha

  if ! base_sha="$(resolve_commit "${base_reference}")"; then
    return 1
  fi

  if ! merge_base_sha="$(git merge-base "${base_sha}" "${head_sha}")"; then
    log_error "'${base_reference}' and ${head_sha} share no history; nothing can be compared"
    return 1
  fi

  printf '%s\n' "${merge_base_sha}"
}

changed_applied_migrations() {
  local base_sha="$1"
  local head_sha="$2"
  local name_status
  local change_status
  local migration_path
  local migration_file

  if ! name_status="$(git diff --name-status --no-renames "${base_sha}" "${head_sha}" -- "${MIGRATION_DIR}")"; then
    log_error "could not list the migration changes between ${base_sha} and ${head_sha}"
    return 1
  fi

  while IFS=$'\t' read -r change_status migration_path; do
    if [ -z "${migration_path}" ]; then
      continue
    fi

    if [ "${change_status}" = "A" ]; then
      continue
    fi

    migration_file="${migration_path##*/}"

    if [[ "${migration_file}" =~ ${VERSIONED_MIGRATION_PATTERN} ]]; then
      printf '%s %s\n' "${change_status}" "${migration_path}"
    fi
  done <<< "${name_status}"
}

check_migrations() {
  local base_sha="$1"
  local head_sha="$2"
  local violations

  if ! git cat-file -e "${head_sha}:${MIGRATION_DIR}" 2>/dev/null; then
    log_error "no migration directory ${MIGRATION_DIR} at ${head_sha}; update MIGRATION_DIR if it moved"
    return 1
  fi

  if ! violations="$(changed_applied_migrations "${base_sha}" "${head_sha}")"; then
    return 1
  fi

  if [ -n "${violations}" ]; then
    log_error "applied migrations are never edited, renamed or deleted; add a new versioned migration instead:"
    indented_to_stderr "${violations}"
    return 1
  fi

  log_ok "no applied migration changed since ${base_sha}"
}

contract_version_at() {
  local commit_sha="$1"
  local contract_text

  if ! git cat-file -e "${commit_sha}:${CONTRACT_FILE}" 2>/dev/null; then
    return 0
  fi

  if ! contract_text="$(git show "${commit_sha}:${CONTRACT_FILE}")"; then
    log_error "could not read ${CONTRACT_FILE} at ${commit_sha}"
    return 1
  fi

  printf '%s\n' "${contract_text}" | awk -v quotes="\"'" '
    /^info:[[:space:]]*$/ { in_info = 1; next }
    in_info && /^[^[:space:]#]/ { exit }
    in_info && /^  version:/ {
      sub(/^  version:[[:space:]]*/, "")
      sub(/[[:space:]]+#.*$/, "")
      gsub("^[" quotes "]|[" quotes "][[:space:]]*$", "")
      print
      exit
    }
  '
}

version_core() {
  local semantic_version="$1"
  local without_build="${semantic_version%%+*}"

  printf '%s\n' "${without_build%%-*}"
}

core_is_lower() {
  local candidate_version="$1"
  local reference_version="$2"
  local candidate_major candidate_minor candidate_patch
  local reference_major reference_minor reference_patch

  IFS='.' read -r candidate_major candidate_minor candidate_patch <<< "$(version_core "${candidate_version}")"
  IFS='.' read -r reference_major reference_minor reference_patch <<< "$(version_core "${reference_version}")"

  if [ "${candidate_major}" -lt "${reference_major}" ]; then
    return 0
  fi

  if [ "${candidate_major}" -gt "${reference_major}" ]; then
    return 1
  fi

  if [ "${candidate_minor}" -lt "${reference_minor}" ]; then
    return 0
  fi

  if [ "${candidate_minor}" -gt "${reference_minor}" ]; then
    return 1
  fi

  if [ "${candidate_patch}" -lt "${reference_patch}" ]; then
    return 0
  fi

  return 1
}

is_semantic_version() {
  local api_version="$1"

  [[ "${api_version}" =~ ${SEMANTIC_VERSION_PATTERN} ]]
}

require_semantic_version() {
  local commit_label="$1"
  local api_version="$2"

  if [ -z "${api_version}" ]; then
    log_error "${commit_label}: no info.version in ${CONTRACT_FILE}"
    return 1
  fi

  if ! is_semantic_version "${api_version}"; then
    log_error "${commit_label}: info.version '${api_version}' is not a semantic version (MAJOR.MINOR.PATCH)"
    return 1
  fi
}

commit_label_of() {
  local commit_sha="$1"
  local commit_subject

  if ! commit_subject="$(git log -1 --format=%s "${commit_sha}")"; then
    log_error "could not read the subject of ${commit_sha}"
    return 1
  fi

  printf 'commit %s (%s)\n' "${commit_sha:0:12}" "${commit_subject}"
}

check_contract_commit() {
  local commit_sha="$1"
  local commit_label
  local new_version
  local previous_version

  if ! commit_label="$(commit_label_of "${commit_sha}")"; then
    return 1
  fi

  if ! new_version="$(contract_version_at "${commit_sha}")"; then
    return 1
  fi

  if ! require_semantic_version "${commit_label}" "${new_version}"; then
    return 1
  fi

  if ! git rev-parse -q --verify "${commit_sha}^" > /dev/null; then
    return 0
  fi

  if ! previous_version="$(contract_version_at "${commit_sha}^")"; then
    return 1
  fi

  if [ -z "${previous_version}" ]; then
    return 0
  fi

  if [ "${new_version}" = "${previous_version}" ]; then
    log_error "${commit_label}: changes ${CONTRACT_FILE} without raising info.version (${new_version})"
    return 1
  fi

  # A previous version that was never valid cannot be compared; raising it to a valid one is the fix.
  if ! is_semantic_version "${previous_version}"; then
    return 0
  fi

  if core_is_lower "${new_version}" "${previous_version}"; then
    log_error "${commit_label}: lowers info.version from ${previous_version} to ${new_version}"
    return 1
  fi
}

check_api_version() {
  local base_sha="$1"
  local head_sha="$2"
  local contract_commits
  local commit_sha
  local head_version
  local checked_commits=0
  local failures=0

  if ! contract_commits="$(git rev-list --reverse "${base_sha}..${head_sha}" -- "${CONTRACT_FILE}")"; then
    log_error "could not list the commits that change ${CONTRACT_FILE}"
    return 1
  fi

  while IFS= read -r commit_sha; do
    if [ -z "${commit_sha}" ]; then
      continue
    fi

    checked_commits=$((checked_commits + 1))

    if ! check_contract_commit "${commit_sha}"; then
      failures=$((failures + 1))
    fi
  done <<< "${contract_commits}"

  if [ "${failures}" -gt 0 ]; then
    return 1
  fi

  if ! head_version="$(contract_version_at "${head_sha}")"; then
    return 1
  fi

  if ! require_semantic_version "head ${head_sha:0:12}" "${head_version}"; then
    return 1
  fi

  if [ "${checked_commits}" -eq 0 ]; then
    log_ok "info.version ${head_version}; ${CONTRACT_FILE} unchanged since ${base_sha}"
    return 0
  fi

  log_ok "info.version ${head_version}; ${checked_commits} contract change(s) since ${base_sha}, each raising it"
}

run_check() {
  local check_name="$1"
  local base_reference="$2"
  local head_reference="$3"
  local head_sha
  local base_sha

  if ! head_sha="$(resolve_commit "${head_reference}")"; then
    return 1
  fi

  if [ -z "${base_reference}" ]; then
    log_warn "the first push to main has no earlier commit; ${check_name} compared nothing"
    return 0
  fi

  if ! base_sha="$(base_commit "${base_reference}" "${head_sha}")"; then
    return 1
  fi

  case "${check_name}" in
    migrations)
      check_migrations "${base_sha}" "${head_sha}"
      ;;
    api-version)
      check_api_version "${base_sha}" "${head_sha}"
      ;;
  esac
}

main() {
  local check_name="${1:-}"
  local base_reference
  local head_reference

  case "${check_name}" in
    migrations | api-version) ;;
    *) usage_error ;;
  esac

  case "$#" in
    3)
      base_reference="$2"
      head_reference="$3"
      ;;
    1)
      if ! base_reference="$(ci_base_reference)"; then
        return 1
      fi

      if ! head_reference="$(ci_head_reference)"; then
        return 1
      fi
      ;;
    *) usage_error ;;
  esac

  run_check "${check_name}" "${base_reference}" "${head_reference}"
}

if ! main "$@"; then
  exit 1
fi
