# Reactive code style

Rules for reactive code in this application: controllers, services, the query layer, security
wiring, and their tests. The general rules in `CLAUDE.md` (explicit control flow, domain names, no
`null`, constructor injection, comments explain *why*) apply unchanged; this document adds what
Reactor, WebFlux and R2DBC make possible to get wrong.

It follows the reactive style of [entity-metamodel](https://github.com/VadimBabich/entity-metamodel),
from the consumer's side. The library holds itself to stricter rules because it cannot see an
application's threads, latency budget or transactions. This application can, so it owns those
decisions: timeouts, retries, caching and transaction boundaries are made here, on purpose, at a
named place.

---

## 1. Prohibitions

Changing one of these needs a decision record in `docs/adr/`.

1. **Never block on a request path.** No `block()`, `blockFirst()`, `blockLast()`, `toIterable()`,
   `toStream()`, `Thread.sleep`, blocking I/O or JDBC. One blocking call on an event-loop thread
   stalls every request that thread serves. JDBC exists here for Flyway at start-up only.
2. **A blocking API is bridged once, at its edge.** When a dependency offers only a blocking client,
   wrap the call in `Mono.fromCallable(...)` with `subscribeOn(Schedulers.boundedElastic())`, inside
   one adapter class named for what it wraps. No other code chooses schedulers: no `publishOn`, no
   `parallel()`, no `Schedulers` in controllers, services or queries.
3. **Never subscribe inside the application.** No `subscribe()` or `toFuture()` in main code.
   Return the publisher; WebFlux subscribes. A manual subscription escapes cancellation, the
   transaction and error handling.
4. **No `onErrorContinue`.** It depends on upstream operators cooperating and silently changes
   semantics. Handle the error where it is understood.
5. **No ambient mutable state.** No static mutable fields, no thread-locals, no lazily initialised
   caches outside a bean built for caching. Reactive work moves between threads; a thread-local does
   not follow it.

## 2. Laziness: assembly is not execution

**Assembly** is when operators are wired together; **subscription** is when work happens. Every
argument passed to an operator is evaluated at assembly, on every call, whether or not that branch
ever runs.

- **Defer a fallback that costs anything.** `switchIfEmpty`, `onErrorResume` and `defaultIfEmpty`
  evaluate their argument eagerly, and building an exception (its stack trace) is expensive:

  ```java
  // WRONG: the exception is built on every call, including when the folder exists
  return folders.find(folderId)
      .switchIfEmpty(folderNotFound(folderId));

  // RIGHT
  return folders.find(folderId)
      .switchIfEmpty(Mono.error(() -> new FolderNotFoundException(folderId)));
  ```

  `Mono.error(Supplier)` and `Mono.defer(Supplier)` are the two correct forms. A constant fallback
  (`Mono.empty()`, an immutable value) needs no deferral.
- **Never wrap a call in `Mono.just(...)`.** `Mono.just(load())` runs `load()` at assembly. Use
  `Mono.fromSupplier(this::load)`, or `Mono.fromCallable` when it throws checked exceptions.
- **A named publisher is a recipe, not a result.** Subscribing to the same `Mono` twice runs its
  query twice:

  ```java
  Mono<Node> folder = nodes.findFolder(folderId);           // one description, not one row

  Mono<Access> access   = folder.flatMap(this::accessOf);
  Mono<Totals> totals   = folder.flatMap(this::totalsOf);   // second round trip
  ```

  Compose once (`folder.flatMap(node -> ...)` doing both, or `zipWhen`). `cache()` also fixes it, and
  is allowed here, but only with a reason stated where it is used.
- **Programmer errors throw, data errors signal.** A `null` argument or an impossible state is an
  `IllegalArgumentException`/`NullPointerException` thrown at once. Anything that depends on data,
  the caller or the database is an `onError` signal inside the returned publisher. Never mix the two
  for the same kind of problem.

## 3. Composition and readability

- **A chain reads as one idea, or it becomes named methods.** `flatMap` inside `flatMap` inside
  `flatMap` is the callback pyramid reactive code was meant to remove. Two levels is a smell; three
  is a defect. Extract each level into a private method that takes the value it needs and returns a
  publisher.
- **Name publishers for the value they will carry**: `visibleChildren`, `grantedFolder`. Never
  `mono1`, `result`, `data`, and never the type (`nodeFlux` says nothing the type does not).
- **No conditional embedded in a return expression**, even with publishers on both sides. Use an
  `if` with an early return, or `filter(...).switchIfEmpty(...)` when the shape really is "absent →
  fail".
- **Bound `flatMap` concurrency over an unbounded source.** The default is 256 inner subscriptions;
  against a pool of ten connections that is pool exhaustion. Pass the concurrency, sized to the pool:
  `flatMap(this::loadVersion, 4)`.
- **Choose the operator that states the order you need.** `flatMap` interleaves; `concatMap` keeps
  order one at a time; `flatMapSequential` runs eagerly and emits in order.
- **`then`, `thenReturn`, `thenMany` when only completion matters**, not a `flatMap` that ignores
  its argument.

## 4. Absence, errors and cancellation

- **Absence becomes an error at the boundary that knows what it means.** The query layer returns an
  empty `Mono` for "no row"; the handler decides whether that is a 404.
- **One exception type per failure mode**, carrying the identifiers needed to act on it, mapped to
  an HTTP status in one place (a `@RestControllerAdvice` producing RFC 9457 problem details). Never
  a bare `RuntimeException`, never a message as the only payload, never data the caller may not see
  in the message.
- **Translate driver failures where they enter our code.** Map a recognised condition (a unique
  violation, a serialisation failure, a lock timeout) onto our own exception with `onErrorMap`, and
  always chain the cause.
- **Narrow error hooks only**: `onErrorResume(SpecificException.class, ...)`, never the catch-all
  form.
- **`doOn*` is for observation**: logging, metrics, tracing. No state changes, no I/O. An exception
  thrown from a `doOn*` callback is a fatal signal error, not a normal `onError`.
- **Cancellation is normal**: it is what happens when a client disconnects. Release resources with
  `usingWhen` or `doFinally` handling complete, error *and* cancel.

## 5. The HTTP boundary

- **Controllers implement the generated interfaces** and stay thin: read the request, call one
  service method, map the result. No query building, no transaction, no business rule in a
  controller.
- **`Mono<T>` for one or none, `Flux<T>` for a stream.** Never `Mono<Optional<T>>`, never
  `Mono<List<T>>` with an unbounded count.
- **Every listing is bounded.** A `limit` with a maximum in the contract and a `LIMIT` in the query;
  a cursor for the next page. Never a whole table as a `Flux` to the wire.
- **Stream deliberately.** Once the first element of a streamed body is written, a later error can
  no longer become a clean status code. Server-sent events and large downloads are the exceptions,
  each designed for it.
- **Timeouts are set on outbound calls.** Every call that leaves the process (S3, Keycloak, another
  service) carries an explicit `timeout()`, and `retryWhen()` with backoff only where the operation
  is idempotent. Database statements are bounded on the server (`statement_timeout`,
  `lock_timeout`), not with a Reactor timeout that abandons a statement still running.

## 6. Context and the caller's identity

- **Pass what the operation needs as parameters.** The tenant, the acting user, the clock: an
  explicit parameter is greppable and testable.
- **Read the principal once, at the edge.** The controller (or an argument resolver) takes the
  authenticated principal from WebFlux (`@AuthenticationPrincipal` or `ReactiveSecurityContextHolder`)
  and passes the user id and tenant down as values. Services below the controller never read the
  security context themselves.
- **The Reactor context is for cross-cutting request values** (security, tracing, the
  transaction), not a bag of domain arguments. Keys are class tokens or dedicated key objects, never
  string literals.

## 7. Transactions

- **Boundaries live on service methods.** `@Transactional` (or `TransactionalOperator`) on the
  service method that owns the unit of work; never on a controller, never on a query helper.
- **A reactive transaction travels in the Reactor context.** Work is inside it only while it stays
  in the returned publisher's composition. Anything subscribed elsewhere, or bridged to another
  scheduler, is outside it; a `@Transactional` method whose publisher is never subscribed does
  nothing.
- **Read paths are `readOnly = true`.**
- **Set transaction-local database settings inside the transaction**, with `set_config(..., true)`,
  as the first statement of the unit of work. A session-level setting leaks to the next request that
  borrows the connection.
- **Conflicts are detected, not assumed away.** Where a version or a precondition guards an update,
  put it in the statement and treat an unexpected affected-row count as a conflict the caller may
  retry.

## 8. Queries

- **Typed queries only.** `FluentSelect` over the generated `*__` metamodels, run by the
  `MetamodelQueryExecutor` bean. No SQL strings in handlers or services (ArchUnit enforces it).
- **`SqlExpr.raw` is the documented escape hatch**, used only where the typed API cannot express the
  query, with a comment saying why, and its text never assembled from request input. Values are
  always bound.
- **Generated code is never edited**: neither the metamodels nor the OpenAPI interfaces. Change the
  entity or the contract and regenerate.

## 9. Tests

- **`StepVerifier` or `WebTestClient` is the assertion.** Assert on signals: `expectNext`,
  `expectComplete`, `expectError(Type.class)`, `thenCancel().verify()`. `block()` collapses a stream
  to one value and asserts nothing about completion, order, error type or cancellation.
- **`block()` is allowed in fixtures and setup only**, where the value is a precondition, never as
  the assertion.
- **Every endpoint has its error paths tested**: the refusal without a token, and each documented
  error response, not only the success path.
- **Time is virtual.** `StepVerifier.withVirtualTime` for delays and timeouts; no `Thread.sleep`,
  no polling.
- **Integration tests run against real PostgreSQL** in Testcontainers, never an in-memory
  substitute: the point of this application is behaviour on the real database.

## 10. Enforcement

Reviews miss these, so the build catches what it can.

1. **ArchUnit** (`ArchitectureTest`): already enforced are no `DatabaseClient` in handler packages
   and no `java.sql` on the request path. Rules for no `block*`/`subscribe`/`onErrorContinue` and no
   `Schedulers` outside the blocking adapters are added as soon as the code they guard exists.
2. **Review checklist for a diff touching a reactive chain:** is every fallback deferred? is a named
   publisher subscribed twice? is `flatMap` concurrency bounded? does the operator state the order
   needed? is cancellation handled in cleanup? is the error path tested?
