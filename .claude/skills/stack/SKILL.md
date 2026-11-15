---
name: stack
description: Start, check or stop the local Docker Compose stack (PostgreSQL, Keycloak, S3, Prometheus, Grafana, the app), and run its end-to-end smoke test.
argument-hint: "[up|smoke|down]"
allowed-tools: Bash(./mvnw *), Bash(docker compose -f deploy/compose/compose.yaml *), Bash(deploy/compose/smoke-test.sh)
---

The stack is defined in `deploy/compose/compose.yaml`. Ports are bound to 127.0.0.1 only.

- **up** (infrastructure only, for running the app from the IDE):
  `docker compose -f deploy/compose/compose.yaml up -d --wait`
- **smoke** (everything, including the app container and observability):
  `./mvnw -B package -DskipTests` and then `deploy/compose/smoke-test.sh`
- **down**: `docker compose -f deploy/compose/compose.yaml --profile app --profile observability down -v`

Endpoints once up: app http://localhost:8080, Keycloak http://localhost:8180 (realm `workspace`),
PostgreSQL localhost:15432, S3 http://localhost:7070, Prometheus http://localhost:9090,
Grafana http://localhost:3000.

Before starting, check that nothing else holds those ports (`docker ps`). If the smoke test fails,
show `docker compose ... logs --tail 200` for the failing service rather than retrying blindly.
