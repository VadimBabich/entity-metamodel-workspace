# Local stack

A Docker Compose stack for development and demonstration. **Not a production deployment**: every
credential is a development default, ports are bound to 127.0.0.1, and Keycloak runs in `start-dev`.

| Profile | Services |
|---|---|
| (none) | PostgreSQL 18 (`postgres`), Keycloak 26 with its own PostgreSQL, Versity S3 Gateway (`s3`) |
| `observability` | Prometheus, Grafana (a provisioned Prometheus data source and an overview dashboard) |
| `app` | the application, built from `workspace-app/target/*.jar` (run `./mvnw package` first) |

```bash
docker compose -f deploy/compose/compose.yaml up -d --wait
docker compose -f deploy/compose/compose.yaml --profile observability --profile app up -d --build --wait
docker compose -f deploy/compose/compose.yaml --profile observability --profile app down -v
```

`smoke-test.sh` starts everything and checks that a Keycloak token reaches the API and that
Prometheus scrapes the application. CI runs it on every push.

## Keycloak

The realm `workspace` is imported at start-up from `keycloak/import/workspace-realm.json`. Its
clients:

- `workspace-ui`: public client, authorization code with PKCE, for the future UI;
- `workspace-dev-cli`: password grant, for `curl` against the local stack only;
- `workspace-sync`: a service account that reads users and organization memberships (`view-users`
  and `view-organizations`, nothing that writes). Its secret is the variable `KEYCLOAK_SYNC_SECRET`,
  with a development default in `compose.yaml`.

A user's access token carries `sub`, `preferred_username`, `email`, the audience `workspace-api`,
exactly one `organization` alias and, for an administrator, the realm role `company-admin`.

| User | Organization | Organization group | Administrator |
|---|---|---|---|
| `alice` | `acme` | `managers` | yes |
| `ivan` | `acme` | none | yes |
| `carol` | `acme` | `listers` | |
| `dave` | `acme` | `viewers` | |
| `erin` | `acme` | `contributors` | |
| `frank` | `acme` | `editors` | |
| `heidi` | `acme` | none | |
| `bob` | `globex` | `editors` | |

Every password equals the user name, and every address is `<user>@example.org`.
`check-identity.sh` asserts all of this against a running Keycloak.

The export defines every client scope it uses, Keycloak's built-in ones included, because an export
that defines any scope gets none of the built-ins. Those definitions are copied from Keycloak
26.8.2's own export, so a Keycloak upgrade needs them compared with the new version's. Never commit
an export made by the server: it carries the realm's private keys, which the secret scan does not
report, and Guards refuses it.

`KC_HOSTNAME` is `http://localhost:8180`, so tokens carry that issuer whether they were requested
from a browser or from a container; the application container fetches keys over the compose network
and still checks the issuer.

### Applying an edit to the realm file

The import runs only while the realm does not exist yet, and Keycloak keeps it in its own database
volume. After the first start, neither `up` nor a restart of Keycloak applies an edit to
`workspace-realm.json`. Remove Keycloak, its database and that volume; the application's data stays:

```bash
docker compose -f deploy/compose/compose.yaml rm -sf keycloak keycloak-db
docker volume rm workspace_keycloak-db-data
docker compose -f deploy/compose/compose.yaml up -d --wait
```

`down -v` also works, but removes every volume, the application's database included. A stack started
before a change to the realm file keeps the old realm, and `check-identity.sh` then reports a token
without `sub` or an unknown user.

## Ports

PostgreSQL is published on 15432 rather than 5432, so it does not collide with a local PostgreSQL;
set `WORKSPACE_DB_PORT` to change it.
