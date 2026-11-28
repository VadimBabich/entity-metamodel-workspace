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

## Data model

| Table | Holds | Written by |
|---|---|---|
| `company` | one row per Keycloak organization, found by its alias | the seed (migration role) |
| `node` | folders and files in one table, so a folder's listing is one index-ordered query; a name is unique among a folder's live children | the seed, until write operations exist |
| `app_user` | one row per user, keyed by the token's `sub`, created on the user's first request | the application |
| `user_group` | the organization groups of Keycloak, mirrored by id | the application |
| `group_membership` | which user is in which group, as last read from Keycloak | the application |
| `acl.access_grant` | one role for one subject (a user, a group or the whole company) on one node | the seed, until grant operations exist |

Every table carries `company_id`, and each foreign key includes it, so a row cannot point into
another company. One reference has no foreign key: a grant's subject, which may be a user, a group or
the company. A read never follows it across companies, because a listing matches only grants of the
caller's company against the caller's own user, groups and company. Trashing a node sets
`deleted_at`; a node is never deleted.

**Two database roles.** `workspace_migrator` owns the schema and runs Flyway; `workspace_app` serves
requests with the privileges each table grants it and no more: it reads `company`, `node` and
`acl.access_grant`, and writes only the three tables it mirrors from Keycloak. Neither is a
superuser.

## Who sees what

- **The company** comes from the token's organization claim. A token naming no organization,
  several, or one the database does not know is refused with 401.
- **A role** is one of lister, viewer, contributor, editor, manager. A grant on a folder reaches
  everything beneath it; the role on an item is the highest among the grants on its ancestors and
  the grants on the item itself.
- **The top level** lists the folders on which the caller holds a grant directly. An administrator
  sees what their grants allow, like anyone else.
- **Not found and not allowed answer alike:** an absent, trashed or foreign item, a file where a
  folder is expected, and a folder the caller may not list all answer 404 `item-not-found`.

The access condition is part of each listing statement, not a filter applied afterwards, so a page
is never shortened by rows the caller may not see.

## Identity mirror

The application keeps its own copy of what it needs from Keycloak, so a listing reads only
PostgreSQL.

- **Groups**: a scheduled pass (every minute; `workspace.mirror.group-pass-interval`) creates and
  renames the group rows of each known company.
- **Memberships**: read for a user on their first request and again on a request once they are older
  than 5 minutes. If Keycloak cannot be reached then, the stored memberships stay in force.
- **Not yet**: removing a deleted group, and refreshing users who send no request. Both arrive with
  the scheduled membership pass.

## Paging

Listings page by keyset: folders first, then by name, then by id, read from an index in that order.
`ListingOrder` holds the order and the row comparison that resumes it, built from one column list.

A page that has a successor carries `nextCursor`, 76 URL-safe characters: a format version, the id
of the page's last item, and a keyed hash of the caller, the company, the folder and the order,
signed with HMAC-SHA256. It holds no name and no readable identifier of the caller. The cursor never
authorises: every page runs the access condition again, and the item the cursor points after must
still be listed for the caller.

| Misuse | Answer |
|---|---|
| tampered, not a cursor, signed with another key, or its item is trashed, moved or no longer visible | 400 `invalid-cursor` |
| reused by another caller, on another folder, or between a folder and the top level | 400 `cursor-of-another-query` |

Paging takes no snapshot: an item renamed or moved between pages may be skipped or listed twice.

## Open decisions

- the storage model for file versions (write-once object keys on an unversioned bucket);
- the S3-compatible store used for local development and tests;
- the browser UI and how it authenticates.
