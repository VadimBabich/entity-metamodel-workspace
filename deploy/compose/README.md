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
Prometheus scrapes the application. `perf-smoke.sh` then runs k6 against that stack, with the load
and the failure threshold set in `perf/smoke.js`. It prints the 95th-percentile response time but
never fails on it. CI runs both on every push.

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

## Database roles

`postgres/initdb/01-roles.sql` creates two roles when the database volume is first initialised; the
same file runs in the integration tests' container:

| Role | Used by | Holds |
|---|---|---|
| `workspace_migrator` | Flyway | owns every table it migrates; `CREATE` on the database and the `public` schema |
| `workspace_app` | the application | `USAGE` on `public` and what each migration grants it; owns nothing |

Neither is a superuser. The image's `workspace` superuser runs the script and nothing else.
`application.yaml` defaults to both roles with the passwords the script sets, so `java -jar` against
this stack needs no database settings.

The script runs only on an empty data directory. A volume created before it existed has neither role
and the application fails at start-up. Remove that volume once, together with the `app` container,
whose image still carries the jar built before the roles, and the `prometheus` container, which keeps
the scrape configuration it loaded at start; then rebuild and start everything:

```bash
docker compose -f deploy/compose/compose.yaml --profile app --profile observability rm -sf postgres app prometheus
docker volume rm workspace_postgres-data
./mvnw -B package -DskipTests
docker compose -f deploy/compose/compose.yaml --profile observability --profile app up -d --build --wait
```

`smoke-test.sh` then checks the stack end to end. Only the application database is lost; Keycloak's
data is in its own volume.

## Demonstration data

A request is answered only for a user whose token names an organization the database knows as a
company. Once the application has started (in compose or by `java -jar`), seed the two companies:

```bash
docker compose -f deploy/compose/compose.yaml --profile seed run --rm seed-companies
```

`seed/companies.sql` waits up to two minutes for the application's migrations, runs as the migration
role, and can be run again without effect. `smoke-test.sh` runs it.

## Ports

PostgreSQL is published on 15432 rather than 5432, so it does not collide with a local PostgreSQL;
set `WORKSPACE_DB_PORT` to change it.

The application serves its API on 8080 and its management endpoints (health, info, the Prometheus
scrape) on 9091. Compose publishes 8080 only: Prometheus scrapes `app:9091` inside the compose
network, the container's healthcheck reads its own port, and `localhost:8080/actuator/*` answers 404.
An application started on the host with `java -jar` serves management on `localhost:9091`, which the
`workspace-host` scrape job reads; set `MANAGEMENT_PORT` to change it.
