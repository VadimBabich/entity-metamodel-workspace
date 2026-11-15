#!/usr/bin/env bash
#
# Fails when the Keycloak realm export would issue tokens without `sub`, or carries what an export
# made by the server carries and a commit must not: key providers, private keys, a literal client
# secret, or the administrator role in the default roles.
#
# Usage:
#   check-realm-export.sh                  # the committed export (Guards workflow)
#   check-realm-export.sh <realm.json>     # another file

set -euo pipefail

readonly DEFAULT_EXPORT='deploy/compose/keycloak/import/workspace-realm.json'
readonly USAGE_ERROR=2
# The only secret a client may carry: the placeholder compose resolves on the Keycloak service.
readonly SECRET_PLACEHOLDER="\${KEYCLOAK_SYNC_SECRET}"

# argv: export file, secret placeholder. Prints one line per problem; exits 2 when the file is not JSON.
readonly PROBLEMS='
import json, sys

PLACEHOLDER = sys.argv[2]
ADMIN_ROLE = "company-admin"

try:
    realm = json.load(open(sys.argv[1]))
except json.JSONDecodeError as error:
    print("not JSON: %s" % error, file=sys.stderr)
    sys.exit(2)

scope_names = []
for scope in realm.get("clientScopes", []):
    scope_names.append(scope.get("name"))
if "basic" not in scope_names:
    print("no basic client scope: tokens would carry no sub")

key_providers = realm.get("components", {}).get("org.keycloak.keys.KeyProvider", [])
if key_providers:
    print("%d key provider component(s): the export came from the server" % len(key_providers))

if "privateKey" in json.dumps(realm):
    print("a privateKey value")

for client in realm.get("clients", []):
    secret = client.get("secret")
    if secret is not None and secret != PLACEHOLDER:
        print("client %s has a literal secret instead of %s" % (client.get("clientId"), PLACEHOLDER))

default_role = realm.get("defaultRole", {}).get("name", "default-roles-" + str(realm.get("realm")))
for role in realm.get("roles", {}).get("realm", []):
    if role.get("name") == default_role and ADMIN_ROLE in role.get("composites", {}).get("realm", []):
        print("%s is a composite of the default role %s" % (ADMIN_ROLE, default_role))
'

if [ "$#" -gt 1 ]; then
  printf 'usage: %s [realm.json]\n' "$0" >&2
  exit "${USAGE_ERROR}"
fi

export_file="${1:-${DEFAULT_EXPORT}}"
if [ ! -f "${export_file}" ]; then
  printf 'ERROR realm export not found: %s\n' "${export_file}" >&2
  exit 1
fi

problems="$(python3 -c "${PROBLEMS}" "${export_file}" "${SECRET_PLACEHOLDER}")"

if [ -z "${problems}" ]; then
  printf 'OK    realm export has the basic scope and no key, literal secret or default admin role (%s)\n' "${export_file}"
  exit 0
fi

printf 'ERROR realm export %s:\n' "${export_file}" >&2
printf '%s\n' "${problems}" | sed 's/^/  /' >&2
exit 1
