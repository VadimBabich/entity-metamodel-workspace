# Architecture

## Purpose

A reference application for entity-metamodel. Each part exists either to exercise the library in a
realistic setting or because a realistic application of this kind needs it. Parts that show nothing
of the library are kept small and optional.

## Building blocks

| Block | Technology | Notes |
|---|---|---|
| API | OpenAPI 3.1 document → generated Spring WebFlux interfaces | contract first; the document is the source |
| Application | Spring Boot 4.1, WebFlux, Java 25 | reactive end to end |
| Queries | entity-metamodel 2.0.0-RC1 from Maven Central | typed `FluentSelect` over generated metamodels |
| Persistence | PostgreSQL 18, Spring Data R2DBC; Flyway over JDBC for migrations only | one `node` table for folders and files |
| File bytes | an S3-compatible store (Versity Gateway locally) | metadata in PostgreSQL, bytes in S3 |
| Identity | Keycloak (OIDC); the application is an OAuth 2 resource server | tokens validated for issuer and audience |
| Observability | Micrometer → Prometheus → Grafana; structured (ECS) logs | `/actuator/prometheus` |

## Modules

```
workspace-contract  →  workspace-app  ←  workspace-domain
   (generated API)      (Spring Boot)      (entities + generated metamodels)
```

`workspace-domain` depends on nothing web; `workspace-contract` holds nothing hand-written.

## Decisions

Architecture decisions are recorded in [`docs/adr/`](adr/). The first ones to write, once made:

- the storage model for file versions (write-once object keys on an unversioned bucket);
- the S3-compatible store used for local development and tests;
- the browser UI and how it authenticates (see [`ui/README.md`](../ui/README.md)).
