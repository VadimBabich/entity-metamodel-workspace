#!/usr/bin/env bash
#
# Runs the k6 load smoke of perf/smoke.js against a running stack with alice's token; the load and the threshold are
# set there. p(95) is printed and, in CI, written to the job summary; it fails nothing. Run from the repository root
# after smoke-test.sh has started the stack:
#
#   deploy/compose/perf-smoke.sh

set -euo pipefail

readonly TOKEN_URL='http://localhost:8180/realms/workspace/protocol/openid-connect/token'
readonly APP_URL='http://app:8080'
readonly COMPOSE_NETWORK='workspace_default'
readonly K6_IMAGE='grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276'

script_dir="$(cd "$(dirname "$0")" && pwd)"
readonly script_dir
readonly K6_SCRIPT="${script_dir}/perf/smoke.js"

readonly ACCESS_TOKEN='
import json, sys
print(json.load(sys.stdin)["access_token"])
'

# argv: summary export file. Prints the request count and p(95) of the request duration in milliseconds.
readonly RUN_SUMMARY_LINE='
import json, sys
metrics = json.load(open(sys.argv[1]))["metrics"]
requests = metrics["http_reqs"]["count"]
p95 = metrics["http_req_duration"]["p(95)"]
print("%d requests, p(95) %.2f ms" % (requests, p95))
'

# Script level, not local to main: the EXIT trap that removes it runs after main has returned.
summary_dir=''

log() {
  printf '[perf] %s\n' "$1" >&2
}

fail() {
  printf '[perf] FAILED: %s\n' "$1" >&2
  exit 1
}

main() {
  local token_response token k6_status run_summary

  [ -f "${K6_SCRIPT}" ] || fail "no k6 script at ${K6_SCRIPT}"

  token_response="$(curl -fsS -X POST "${TOKEN_URL}" \
    -d grant_type=password -d client_id=workspace-dev-cli -d username=alice -d password=alice)" \
    || fail "Keycloak issued no token; is the stack running?"
  token="$(printf '%s' "${token_response}" | python3 -c "${ACCESS_TOKEN}" 2> /dev/null)" \
    || fail "Keycloak's response held no access token"

  summary_dir="$(mktemp -d)"
  trap 'rm -rf "${summary_dir}"' EXIT

  # The token is passed by name only, so it never appears on a command line.
  k6_status=0
  TOKEN="${token}" docker run --rm -i \
    --network "${COMPOSE_NETWORK}" \
    --user "$(id -u):$(id -g)" \
    -e TOKEN -e BASE_URL="${APP_URL}" \
    -v "${summary_dir}:/out" \
    "${K6_IMAGE}" run --summary-export /out/summary.json - < "${K6_SCRIPT}" \
    || k6_status=$?

  [ -s "${summary_dir}/summary.json" ] || fail "k6 wrote no summary (exit ${k6_status})"
  run_summary="$(python3 -c "${RUN_SUMMARY_LINE}" "${summary_dir}/summary.json" 2> /dev/null)" \
    || fail "k6's summary lacks the request count or p(95) (k6 exit ${k6_status})"
  log "${run_summary}"

  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '## k6 smoke\n\n%s; reported only.\n' "${run_summary}" >> "${GITHUB_STEP_SUMMARY}"
  fi

  [ "${k6_status}" -eq 0 ] || fail "k6 exited ${k6_status}; its output above names the failed threshold or error"
  log "the folder listing held under load"
}

main "$@"
