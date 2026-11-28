#!/usr/bin/env bash
#
# End-to-end check of the local stack: the demonstration data is seeded, the demonstration identities
# pass check-identity.sh, each user lists exactly the folders their groups are granted, other
# companies' folders and unknown ones answer 404, and Prometheus scrapes the application. Run from the
# repository root after
# `./mvnw -B package -DskipTests`:
#
#   deploy/compose/smoke-test.sh
#
# Starts the stack with every profile and leaves it running; `docker compose -f
# deploy/compose/compose.yaml --profile app --profile observability down -v` removes it.

set -euo pipefail

source "$(dirname "$0")/keycloak-tokens.sh"

readonly COMPOSE=(docker compose -f "${COMPOSE_FILE}" --profile app --profile observability)
readonly API_URL='http://localhost:8080'
readonly TOP_LEVEL_PATH='/api/v1/top-level-folders'
readonly ATTEMPTS=60
readonly UNKNOWN_FOLDER_ID=9007199254740991

readonly LISTINGS=(
  'alice acme managers=true'
  'carol acme listers=false'
  'dave acme viewers=true'
  'erin acme contributors=true'
  'frank acme editors=true'
  'ivan acme -'
  'heidi acme -'
  'bob globex editors=true'
)

readonly NAMES_AND_READ='
import json, sys
items = json.load(sys.stdin)["items"]
print(",".join(sorted(item["name"] + "=" + str(item["capabilities"]["read"]).lower() for item in items)))
'

readonly ITEMS_IN_ORDER='
import json, sys
print(",".join(item["kind"] + ":" + item["name"] for item in json.load(sys.stdin)["items"]))
'

readonly ITEM_IDS='
import json, sys
for item in json.load(sys.stdin)["items"]:
    print(item["id"])
'

readonly NEXT_CURSOR='
import json, sys
print(json.load(sys.stdin).get("nextCursor", ""))
'

log() {
  printf '[smoke] %s\n' "$1" >&2
}

fail() {
  printf '[smoke] FAILED: %s\n' "$1" >&2
  exit 1
}

http_get() {
  local token="$1"
  local path="$2"

  http_call -H "Authorization: Bearer ${token}" "${API_URL}${path}"
}

# $1: a query that selects one value.
query_value() {
  "${COMPOSE[@]}" exec -T postgres psql -U workspace -d workspace -tAc "$1"
}

page_at() {
  local username="$1"
  local token="$2"
  local path="$3"
  local response

  response="$(http_get "${token}" "${path}")"
  if [ "$(status_of "${response}")" != "200" ]; then
    fail "${username}'s GET ${path} answered HTTP $(status_of "${response}"), expected 200"
  fi
  body_of "${response}"
}

check_top_level() {
  local username="$1"
  local expected_listing="$2"
  local token
  local request
  local page
  local listing

  if ! token="$(user_token "${username}")"; then
    fail "${username} has no token"
  fi
  for request in first second; do
    if ! page="$(page_at "${username}" "${token}" "${TOP_LEVEL_PATH}")"; then
      fail "${username}'s ${request} top-level listing could not be read"
    fi
    listing="$(printf '%s' "${page}" | python3 -c "${NAMES_AND_READ}")"
    if [ "${listing}" != "${expected_listing}" ]; then
      fail "${username}'s ${request} top-level listing is [${listing}], expected [${expected_listing}]"
    fi
  done
  printf '%s' "${page}" | python3 -c "${ITEM_IDS}"
}

check_not_found() {
  local username="$1"
  local token="$2"
  local folder_id="$3"
  local outcome

  outcome="$(curl -s -o /dev/null -w '%{http_code} %{content_type}' -H "Authorization: Bearer ${token}" \
    "${API_URL}/api/v1/folders/${folder_id}/children")"
  if [ "${outcome}" != "404 application/problem+json" ]; then
    fail "${username}'s listing of folder ${folder_id} answered [${outcome}], expected a 404 problem"
  fi
}

check_listings() {
  local entry
  local username
  local company
  local folder
  local expected_listing
  local folder_ids
  local acme_folder_ids=()
  local globex_folder_ids=()
  local bob_token
  local folder_id

  for entry in "${LISTINGS[@]}"; do
    read -r username company folder <<< "${entry}"
    expected_listing="${folder}"
    if [ "${folder}" = "-" ]; then
      expected_listing=''
    fi

    folder_ids="$(check_top_level "${username}" "${expected_listing}")"
    if [ -z "${folder_ids}" ]; then
      continue
    fi
    if [ "${company}" = "acme" ]; then
      acme_folder_ids+=("${folder_ids}")
    else
      globex_folder_ids+=("${folder_ids}")
    fi
  done
  [ "${#acme_folder_ids[@]}" -eq 5 ] || fail "found ${#acme_folder_ids[@]} acme folders through the group grants, expected 5"
  log "every user lists exactly the folders their groups are granted, with their role's read capability, twice"

  for folder_id in "${globex_folder_ids[@]}"; do
    if is_one_of "${folder_id}" "${acme_folder_ids[@]}"; then
      fail "bob, of globex, lists acme's folder ${folder_id}"
    fi
  done
  bob_token="$(user_token bob)"
  for folder_id in "${acme_folder_ids[@]}"; do
    check_not_found bob "${bob_token}" "${folder_id}"
  done
  log "bob, of globex, lists no acme folder and is answered 404 for each of them"
}

is_one_of() {
  local wanted="$1"
  local candidate

  shift
  for candidate in "$@"; do
    if [ "${candidate}" = "${wanted}" ]; then
      return 0
    fi
  done
  return 1
}

check_ungranted_folder() {
  local unshared_id
  local ivan_token

  if ! unshared_id="$(query_value \
    "select node.node_id from node join company using (company_id) where company.alias = 'acme' and node.name = 'unshared'")"; then
    fail "the unshared folder's id could not be read"
  fi
  [ -n "${unshared_id}" ] || fail "acme has no unshared folder"

  ivan_token="$(user_token ivan)"
  check_not_found ivan "${ivan_token}" "${unshared_id}"
  log "ivan, an administrator without a grant, is answered 404 for the unshared folder"
}

check_children() {
  local token
  local folder_id
  local page
  local items

  token="$(user_token frank)"
  page="$(page_at frank "${token}" "${TOP_LEVEL_PATH}")"
  folder_id="$(printf '%s' "${page}" | python3 -c "${ITEM_IDS}")"
  page="$(page_at frank "${token}" "/api/v1/folders/${folder_id}/children")"
  items="$(printf '%s' "${page}" | python3 -c "${ITEMS_IN_ORDER}")"
  if [ "${items}" != "FOLDER:drafts,FILE:brief.txt,FILE:budget.txt" ]; then
    fail "frank's folder lists [${items}], expected its subfolder first, then its two files by name"
  fi
  log "frank lists his folder's children, folders first"

  check_second_page frank "${token}" "/api/v1/folders/${folder_id}/children"
}

check_second_page() {
  local username="$1"
  local token="$2"
  local path="$3"
  local page
  local cursor
  local items

  page="$(page_at "${username}" "${token}" "${path}?limit=2")"
  cursor="$(printf '%s' "${page}" | python3 -c "${NEXT_CURSOR}")"
  [ -n "${cursor}" ] || fail "${username}'s first page of two items carries no next cursor"

  page="$(page_at "${username}" "${token}" "${path}?limit=2&cursor=${cursor}")"
  items="$(printf '%s' "${page}" | python3 -c "${ITEMS_IN_ORDER}")"
  cursor="$(printf '%s' "${page}" | python3 -c "${NEXT_CURSOR}")"
  if [ "${items}" != "FILE:budget.txt" ] || [ -n "${cursor}" ]; then
    fail "${username}'s second page lists [${items}] with cursor [${cursor}], expected only budget.txt and no cursor"
  fi
  log "${username} pages through his folder with the cursor; the last page carries none"
}

check_stored_subject() {
  local token
  local subject_in_token
  local stored_subject

  token="$(user_token alice)"
  subject_in_token="$(printf '%s' "${token}" | token_subject)"
  if ! stored_subject="$(query_value "select sub from app_user where username = 'alice'")"; then
    fail "alice's user row could not be read"
  fi
  if [ "${stored_subject}" != "${subject_in_token}" ]; then
    fail "alice's user row holds sub [${stored_subject}], her token carries [${subject_in_token}]"
  fi
  log "alice's user row holds her token's sub"
}

check_refusals() {
  local alice_token
  local sync
  local status

  alice_token="$(user_token alice)"
  check_not_found alice "${alice_token}" "${UNKNOWN_FOLDER_ID}"
  log "an unknown folder is answered 404 with a problem body"

  sync="$(sync_token)"
  status="$(status_of "$(http_get "${sync}" "${TOP_LEVEL_PATH}")")"
  [ "${status}" = "401" ] || fail "a workspace-sync token answered ${status}, expected 401"
  log "a workspace-sync token is refused (401)"
}

wait_for() {
  local description="$1"
  local url="$2"
  local attempt

  for attempt in $(seq 1 "${ATTEMPTS}"); do
    if curl -fsS -o /dev/null "${url}"; then
      log "${description} is up"
      return 0
    fi
    sleep 2
  done
  fail "${description} did not answer at ${url}"
}

if ! ls workspace-app/target/workspace-app-*.jar > /dev/null 2>&1; then
  fail "no packaged jar; run ./mvnw -B package -DskipTests first"
fi

"${COMPOSE[@]}" up -d --build --wait

if ! "${COMPOSE[@]}" kill -s SIGHUP prometheus > /dev/null; then
  fail "Prometheus could not be told to re-read its mounted configuration"
fi
log "Prometheus re-read its mounted configuration; a running container keeps the one it started with"

"${COMPOSE[@]}" --profile seed run --rm seed-companies > /dev/null
log "the demonstration companies are seeded"

"${COMPOSE[@]}" --profile seed run --rm seed-content > /dev/null
log "the demonstration folders and grants are seeded"

"$(dirname "$0")/check-identity.sh" || fail "the demonstration identities are not as expected"

status="$(curl -s -o /dev/null -w '%{http_code}' "${API_URL}/actuator/prometheus")"
[ "${status}" = "404" ] || fail "the application port answered ${status} to the scrape path, expected 404"
log "the application port serves no management endpoint (404)"

status="$(curl -s -o /dev/null -w '%{http_code}' "${API_URL}${TOP_LEVEL_PATH}")"
[ "${status}" = "401" ] || fail "a call without a token answered ${status}, expected 401"
log "a call without a token is refused (401)"

check_listings
check_ungranted_folder
check_children
check_stored_subject
check_refusals

wait_for "Prometheus" "http://localhost:9090/-/ready"
for attempt in $(seq 1 "${ATTEMPTS}"); do
  targets_response="$(curl -fsS 'http://localhost:9090/api/v1/targets?scrapePool=workspace')"
  health="$(printf '%s' "${targets_response}" \
    | python3 -c 'import json, sys; t = json.load(sys.stdin)["data"]["activeTargets"]; print(t[0]["health"] if t else "none")')"
  if [ "${health}" = "up" ]; then
    log "Prometheus scrapes the application"
    break
  fi
  [ "${attempt}" -lt "${ATTEMPTS}" ] || fail "Prometheus target for the application is ${health}"
  sleep 2
done

wait_for "Grafana" "http://localhost:3000/api/health"
log "all checks passed"
