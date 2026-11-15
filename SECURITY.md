# Security policy

## Reporting a vulnerability

Report it privately through GitHub:
[open a security advisory](https://github.com/VadimBabich/entity-metamodel-workspace/security/advisories/new).
Do not open a public issue.

A report about the entity-metamodel library itself belongs in
[its repository](https://github.com/VadimBabich/entity-metamodel/security/advisories/new).

## Scope

This is a reference application. Only the `main` branch is maintained. The Docker Compose stack in
`deploy/compose/` uses development credentials and is not a deployment template; findings that
depend on those defaults are out of scope.
