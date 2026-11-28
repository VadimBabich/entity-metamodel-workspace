#!/usr/bin/env bash
#
# Token helpers shared by the local stack's check scripts, which source this file. Each function prints
# its result on stdout, or a reason on stderr and returns 1; the caller decides how to fail.

readonly COMPOSE_FILE='deploy/compose/compose.yaml'
readonly KEYCLOAK_URL='http://localhost:8180'
readonly TOKEN_URL="${KEYCLOAK_URL}/realms/workspace/protocol/openid-connect/token"

readonly ACCESS_TOKEN='
import json, sys
print(json.load(sys.stdin)["access_token"])
'

readonly SYNC_SECRET='
import json, sys
print(json.load(sys.stdin)["services"]["keycloak"]["environment"]["KEYCLOAK_SYNC_SECRET"])
'

readonly DECODE_TOKEN='
import base64, json, sys
token = sys.stdin.read().strip()
payload = token.split(".")[1]
payload += "=" * (-len(payload) % 4)
claims = json.loads(base64.urlsafe_b64decode(payload))
'

readonly TOKEN_SUBJECT="${DECODE_TOKEN}"'
print(claims["sub"])
'

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

# stdin: an access token. Prints its sub claim.
token_subject() {
  python3 -c "${TOKEN_SUBJECT}"
}

# $1: whom the token is for; $2: an http_call response from the token endpoint.
access_token_of() {
  local holder="$1"
  local response="$2"

  if [ "$(status_of "${response}")" != "200" ]; then
    printf 'no token for %s (HTTP %s)\n' "${holder}" "$(status_of "${response}")" >&2
    return 1
  fi
  body_of "${response}" | python3 -c "${ACCESS_TOKEN}"
}

user_token() {
  local username="$1"
  local response

  response="$(http_call -X POST "${TOKEN_URL}" -d grant_type=password -d client_id=workspace-dev-cli \
    -d "username=${username}" -d "password=${username}")"
  access_token_of "${username}" "${response}"
}

sync_token() {
  local compose_config
  local secret
  local response

  if ! compose_config="$(docker compose -f "${COMPOSE_FILE}" config --format json)"; then
    printf 'the compose configuration could not be read for the workspace-sync secret\n' >&2
    return 1
  fi
  if ! secret="$(printf '%s' "${compose_config}" | python3 -c "${SYNC_SECRET}")" || [ -z "${secret}" ]; then
    printf 'the compose configuration holds no workspace-sync secret\n' >&2
    return 1
  fi
  response="$(http_call -X POST "${TOKEN_URL}" -d grant_type=client_credentials -d client_id=workspace-sync \
    --data-urlencode "client_secret=${secret}")"
  access_token_of workspace-sync "${response}"
}
