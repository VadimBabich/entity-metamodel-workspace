#!/usr/bin/env bash
#
# Checks the demonstration identities of the local Keycloak: every user's access token carries the
# claims the application relies on, and the workspace-sync service account reads organization
# memberships the way the application will. Needs only Keycloak:
#
#   docker compose -f deploy/compose/compose.yaml up -d --wait keycloak
#   deploy/compose/check-identity.sh
#
# smoke-test.sh runs it against the whole stack.

set -euo pipefail

readonly COMPOSE_FILE='deploy/compose/compose.yaml'
readonly KEYCLOAK_URL='http://localhost:8180'
readonly TOKEN_URL="${KEYCLOAK_URL}/realms/workspace/protocol/openid-connect/token"
readonly ADMIN_URL="${KEYCLOAK_URL}/admin/realms/workspace"
readonly AUDIENCE='workspace-api'
readonly ADMIN_ROLE='company-admin'
readonly STALE_REALM_HINT='the realm in Keycloak may predate the realm file: Keycloak imports it only when the realm does not exist yet; see "Applying an edit to the realm file" in deploy/compose/README.md'

# username, organization alias, administrator (admin|-), organization group (- for none).
readonly IDENTITIES=(
  'alice acme admin managers'
  'ivan acme admin -'
  'carol acme - listers'
  'dave acme - viewers'
  'erin acme - contributors'
  'frank acme - editors'
  'heidi acme - -'
  'bob globex - editors'
)

readonly DECODE_TOKEN='
import base64, json, sys
token = sys.stdin.read().strip()
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
'

# argv: username, organization alias, administrator (admin|-), audience, admin role. Prints one line
# per problem, nothing when the token is as expected.
readonly CLAIM_PROBLEMS="${DECODE_TOKEN}"'
username, organization, administrator, audience, admin_role = sys.argv[1:6]

if not claims.get("sub"):
    print("no sub")
if claims.get("preferred_username") != username:
    print("preferred_username is %r" % claims.get("preferred_username"))
if claims.get("email") != username + "@example.org":
    print("email is %r" % claims.get("email"))
if claims.get("organization") != [organization]:
    print("organization is %r, expected [%r]" % (claims.get("organization"), organization))
if claims.get("aud") != audience:
    print("aud is %r, expected %r" % (claims.get("aud"), audience))

roles = claims.get("realm_access", {}).get("roles", [])
holds_admin_role = admin_role in roles
if administrator == "admin" and not holds_admin_role:
    print("%s is missing" % admin_role)
if administrator != "admin" and holds_admin_role:
    print("%s is present on a non-administrator" % admin_role)
'

readonly TOKEN_SUBJECT="${DECODE_TOKEN}"'
print(claims["sub"])
'

readonly ACCESS_TOKEN='
import json, sys
print(json.load(sys.stdin)["access_token"])
'

# Prints "alias id" per organization.
readonly ORGANIZATIONS='
import json, sys
for organization in json.load(sys.stdin):
    print(organization["alias"], organization["id"])
'

# Prints the group names, comma-separated, sorted; an empty line for none.
readonly GROUP_NAMES='
import json, sys
print(",".join(sorted(group["name"] for group in json.load(sys.stdin))))
'

readonly SYNC_SECRET='
import json, sys
print(json.load(sys.stdin)["services"]["keycloak"]["environment"]["KEYCLOAK_SYNC_SECRET"])
'

log() {
  printf '[identity] %s\n' "$1" >&2
}

fail() {
  printf '[identity] FAILED: %s\n' "$1" >&2
  exit 1
}

# $@: curl arguments. Prints the body, then the HTTP status on a last line of its own.
http_call() {
  curl -s -w '\n%{http_code}' "$@"
}

status_of() {
  printf '%s' "${1##*$'\n'}"
}

body_of() {
  printf '%s' "${1%$'\n'*}"
}

user_token() {
  local username="$1"
  local response

  response="$(http_call -X POST "${TOKEN_URL}" -d grant_type=password -d client_id=workspace-dev-cli \
    -d "username=${username}" -d "password=${username}")"
  if [ "$(status_of "${response}")" != "200" ]; then
    fail "no token for ${username} (HTTP $(status_of "${response}")); ${STALE_REALM_HINT}"
  fi
  body_of "${response}" | python3 -c "${ACCESS_TOKEN}"
}

sync_token() {
  local compose_config
  local secret
  local response

  compose_config="$(docker compose -f "${COMPOSE_FILE}" config --format json)"
  secret="$(printf '%s' "${compose_config}" | python3 -c "${SYNC_SECRET}")"
  response="$(http_call -X POST "${TOKEN_URL}" -d grant_type=client_credentials -d client_id=workspace-sync \
    --data-urlencode "client_secret=${secret}")"
  if [ "$(status_of "${response}")" != "200" ]; then
    fail "no token for workspace-sync (HTTP $(status_of "${response}")); ${STALE_REALM_HINT}"
  fi
  body_of "${response}" | python3 -c "${ACCESS_TOKEN}"
}

# $1: bearer token; $2: path below the realm's Admin API. Prints the body; fails unless 200.
admin_get() {
  local response

  response="$(http_call -H "Authorization: Bearer $1" "${ADMIN_URL}$2")"
  if [ "$(status_of "${response}")" != "200" ]; then
    fail "GET $2 answered HTTP $(status_of "${response}")"
  fi
  body_of "${response}"
}

check_token_claims() {
  local username="$1"
  local organization="$2"
  local administrator="$3"
  local token="$4"
  local problems

  problems="$(printf '%s' "${token}" | python3 -c "${CLAIM_PROBLEMS}" \
    "${username}" "${organization}" "${administrator}" "${AUDIENCE}" "${ADMIN_ROLE}")"
  if [ -z "${problems}" ]; then
    return 0
  fi
  if printf '%s\n' "${problems}" | grep -q '^no sub$'; then
    problems="${problems}; ${STALE_REALM_HINT}"
  fi
  fail "${username}'s token: ${problems//$'\n'/; }"
}

# $1: sync token; $2: username; $3: user id; $4: expected alias; $5: expected groups (comma-separated).
check_membership() {
  local sync="$1"
  local username="$2"
  local user_id="$3"
  local expected_alias="$4"
  local expected_groups="$5"
  local response
  local organizations
  local alias
  local organization_id
  local groups

  response="$(admin_get "${sync}" "/organizations/members/${user_id}/organizations")"
  organizations="$(printf '%s' "${response}" | python3 -c "${ORGANIZATIONS}")"
  alias="${organizations%% *}"
  organization_id="${organizations#* }"
  if [ "${organizations}" != "${alias} ${organization_id}" ] || [ "${alias}" != "${expected_alias}" ]; then
    fail "${username}'s organizations are [${organizations//$'\n'/; }], expected exactly ${expected_alias}"
  fi

  response="$(admin_get "${sync}" "/organizations/${organization_id}/members/${user_id}/groups")"
  groups="$(printf '%s' "${response}" | python3 -c "${GROUP_NAMES}")"
  if [ "${groups}" != "${expected_groups}" ]; then
    fail "${username}'s organization groups are [${groups}], expected [${expected_groups}]"
  fi
}

# The user ids, recorded as username=sub while the tokens are checked.
declare -a subjects=()

subject_of() {
  local wanted="$1"
  local entry

  for entry in "${subjects[@]}"; do
    if [ "${entry%%=*}" = "${wanted}" ]; then
      printf '%s' "${entry#*=}"
      return 0
    fi
  done
  fail "no subject recorded for ${wanted}"
}


for identity in "${IDENTITIES[@]}"; do
  read -r username organization administrator _ <<< "${identity}"
  token="$(user_token "${username}")"
  check_token_claims "${username}" "${organization}" "${administrator}" "${token}"
  subjects+=("${username}=$(printf '%s' "${token}" | python3 -c "${TOKEN_SUBJECT}")")
done
log "every demonstration user's token carries sub, preferred_username, email, one organization and the right role"

sync="$(sync_token)"

# Grouped members first: an empty list is right for a member in no group, and is also what a service
# account with too few roles gets back for everyone.
for identity in "${IDENTITIES[@]}"; do
  read -r username organization _ group <<< "${identity}"
  if [ "${group}" != "-" ]; then
    check_membership "${sync}" "${username}" "$(subject_of "${username}")" "${organization}" "${group}"
  fi
done
log "workspace-sync reads every grouped member's organization and group"

for identity in "${IDENTITIES[@]}"; do
  read -r username organization _ group <<< "${identity}"
  if [ "${group}" = "-" ]; then
    check_membership "${sync}" "${username}" "$(subject_of "${username}")" "${organization}" ''
  fi
done
log "members in no group have an organization and no group"

update="$(http_call -X PUT -H "Authorization: Bearer ${sync}" -H 'Content-Type: application/json' \
  -d '{"firstName": "Carol"}' "${ADMIN_URL}/users/$(subject_of carol)")"
[ "$(status_of "${update}")" = "403" ] || fail "workspace-sync changed a user (HTTP $(status_of "${update}")), expected 403"
log "workspace-sync is refused a user change (403)"

alice_token="$(user_token alice)"
own="$(http_call -H "Authorization: Bearer ${alice_token}" \
  "${ADMIN_URL}/organizations/members/$(subject_of alice)/organizations")"
[ "$(status_of "${own}")" = "401" ] || fail "a user's own token read the Admin API (HTTP $(status_of "${own}")), expected 401"
log "a user's own token is refused on the Admin API (401)"
