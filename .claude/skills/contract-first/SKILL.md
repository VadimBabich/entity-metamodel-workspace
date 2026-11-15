---
name: contract-first
description: Add or change an API operation the contract-first way - the OpenAPI document first, generated interfaces second, a typed query and a test third. Use whenever an endpoint is added or changed.
---

The API is defined by `workspace-contract/src/main/openapi/workspace.yaml`. Code never invents
contract: interfaces and models are generated from it, and nothing in `workspace-contract` is
written by hand.

1. **Contract.** Edit `workspace.yaml`: the path, the operation id, parameters with their bounds
   (`minimum`, `maximum`, `maxLength`), the responses including errors. Schemas go under
   `components/schemas`.
2. **Generate.** `./mvnw -B -pl workspace-contract install`. Read the generated interface under
   `workspace-contract/target/generated-sources/openapi` before writing anything against it.
3. **Implement.** A controller in `workspace-app` implements the generated interface. Queries are
   built with the entity-metamodel API (`FluentSelect` over the generated `*__` metamodels) and run by
   the `MetamodelQueryExecutor` bean; no SQL strings in handlers (an ArchUnit rule enforces it). A raw
   fragment (`SqlExpr.raw`) is the documented escape hatch: use it only where the typed API cannot
   express the query, and never build its text from request input.
4. **Test.** An integration test through `WebTestClient` against Testcontainers, covering the
   success path, the refusal without a token, and each documented error.
5. **Verify.** Run the `verify` skill.
