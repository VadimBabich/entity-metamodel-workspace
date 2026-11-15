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

The realm `workspace` is imported at start-up from `keycloak/import/workspace-realm.json`:

- client scope `workspace-api` adds the audience `workspace-api`, which the application requires;
- `workspace-ui`: public client, authorization code with PKCE, for the future UI;
- `workspace-dev-cli`: password grant, for `curl` against the local stack only;
- users `alice` and `bob` (passwords equal to the user name).

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

`down -v` also works, but removes every volume, the application's database included.

## Ports

PostgreSQL is published on 15432 rather than 5432, so it does not collide with a local PostgreSQL;
set `WORKSPACE_DB_PORT` to change it.
