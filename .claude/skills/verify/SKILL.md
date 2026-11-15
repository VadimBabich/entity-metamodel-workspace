---
name: verify
description: Run this repository's full quality gate before claiming a change is done or committing it. Use after any code, contract, migration or compose change.
allowed-tools: Bash(./mvnw *), Bash(shellcheck *), Bash(actionlint*), Bash(docker compose -f deploy/compose/compose.yaml config*)
---

Run each step and report its real result. Stop at the first failure, show the relevant output,
and fix the cause; never weaken a test or a check to get past it.

1. `./mvnw -B verify` — unit tests, ArchUnit rules, and the integration tests against PostgreSQL in
   Testcontainers. Docker must be running.
2. If a shell script or `.githooks/*` changed: `shellcheck -S style` on each changed file.
3. If a workflow changed: `actionlint`.
4. If anything under `deploy/compose/` changed:
   `docker compose -f deploy/compose/compose.yaml --profile app --profile observability config -q`,
   then the `stack` skill's smoke test.

Report which steps ran, which were skipped and why, and the outcome of each.
