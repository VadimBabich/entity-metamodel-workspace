#!/usr/bin/env bash
#
# End-to-end check of the local stack: Keycloak issues a token, the demonstration identities pass
# check-identity.sh, the application accepts the token and answers from PostgreSQL, Prometheus
# scrapes the application. Run from the repository root after
# `./mvnw -B package -DskipTests`:
#
#   deploy/compose/smoke-test.sh
#
# Starts the stack with every profile and leaves it running; `docker compose -f
# deploy/compose/compose.yaml --profile app --profile observability down -v` removes it.

set -euo pipefail

readonly COMPOSE=(docker compose -f deploy/compose/compose.yaml --profile app --profile observability)
readonly TOKEN_URL='http://localhost:8180/realms/workspace/protocol/openid-connect/token'
readonly API_URL='http://localhost:8080'
readonly ATTEMPTS=60

log() {
  printf '[smoke] %s\n' "$1" >&2
}

fail() {
  printf '[smoke] FAILED: %s\n' "$1" >&2
  exit 1
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

wait_for "the application" "${API_URL}/actuator/health/readiness"

token_response="$(curl -fsS -X POST "${TOKEN_URL}" \
  -d grant_type=password -d client_id=workspace-dev-cli -d username=alice -d password=alice)"
token="$(printf '%s' "${token_response}" | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])')"
[ -n "${token}" ] || fail "Keycloak issued no token"
log "Keycloak issued a token for alice"

"$(dirname "$0")/check-identity.sh" || fail "the demonstration identities are not as expected"

status="$(curl -s -o /dev/null -w '%{http_code}' "${API_URL}/api/v1/folders/1/children")"
[ "${status}" = "401" ] || fail "a call without a token answered ${status}, expected 401"
log "a call without a token is refused (401)"

status="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${token}" "${API_URL}/api/v1/folders/1/children")"
[ "${status}" = "200" ] || fail "a call with alice's token answered ${status}, expected 200"
log "a call with alice's token is answered (200)"

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
