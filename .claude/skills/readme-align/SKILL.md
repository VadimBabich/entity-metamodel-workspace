---
name: readme-align
description: Align README.md and the other front-door documents with what the current change actually altered. Use after a change to capabilities, the stack, versions, endpoints or how to run the application.
argument-hint: "[git ref or path; defaults to the working tree plus commits since main]"
---

# README alignment

Write as the maintainer of the project's front door. A developer should see within thirty seconds
what this application shows about entity-metamodel and how to run it. Calm, concrete, one engineer
to another.

An alignment pass, not a rewrite for its own sake. Work out what the change altered, then fix every
document that now describes something else.

Scope: $ARGUMENTS. If empty, the working tree plus the commits on this branch since `main`.

## 1. Read the change before the documents

`git diff main...HEAD` and `git status`. Classify what moved:

- an endpoint or capability appeared, changed or was removed
- the status word changed (skeleton, working, complete)
- a version moved: Java, Spring Boot, entity-metamodel, PostgreSQL, Keycloak, an image tag
- the stack changed: a service, a port, a profile, a credential default
- a limitation was added or removed

If nothing moved, the documents are already correct. Say so and stop.

## 2. Verify against the code, never against the other documents

- Endpoints from `workspace.yaml`; versions from the POMs and `deploy/compose/compose.yaml`; ports
  and credentials from the compose file and the realm import.
- Every code example compiles against the current code or is copied from it. An example found
  nowhere in the repository is fiction.
- A capability with no test behind it gets a test named or the claim softened.
- Nothing is published: never write an install step that implies an artifact on a registry.
- Read the private records mapped in `CLAUDE.local.md` for intent; never cite them. No email
  addresses.

## 3. The documents to sweep together

`README.md` (front door), `docs/architecture.md`, `deploy/compose/README.md` (the stack),
`SECURITY.md` and `CONTRIBUTING.md` where the change touches them. After the sweep,
versions, ports, status and stated limits read the same in all of them.

## 4. Standard

- Story order in `README.md`: what this is → run it → the library in this application → layout →
  what it does not do → contributing.
- Show value with examples, not adjectives. Short paragraphs, plain English. Banned: "aims to",
  "powerful and flexible", "enterprise-grade", "robust solution", "it is important to note".
- State limitations plainly.
- Link deeper documents rather than repeating them. Wrap prose at 100 columns.

## 5. Check

Every relative link resolves. If code changed too, run the `verify` skill.

## Report

What the change altered, which claims were verified, which corrected, what was cut, and what was
softened for want of a test.
