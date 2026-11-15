---
name: docs-cleanup
description: Reduce comments and JavaDoc to what earns its place, in Java, SQL, shell, YAML and POM files. Use when asked to clean up comments, or before review of a change that added many.
argument-hint: "[path or glob; defaults to files changed against main]"
---

# Documentation cleanup

A reduction task, not a documentation-writing task. Assume a comment is unnecessary until it proves
otherwise; let the code carry intent. The rules are `CLAUDE.md` § Comments, in every file type.

Scope: $ARGUMENTS. If empty, the files changed against `main`
(`git diff --name-only main...HEAD` plus `git status --short`). Do not edit outside the scope.

## Never touch

- Generated code: anything under `target/`, the OpenAPI interfaces and models, the `*__` metamodels.
- `description` fields in `workspace.yaml`: they are API documentation, shipped in generated code.
- Licence headers.

## Delete

- Comments that describe the next line: `// validate input`, `// return result`, `# start the stack`.
- JavaDoc that restates the signature: on getters, setters, constructors, records, delegation,
  mapping, stream pipelines, logging, validation, wiring.
- Boilerplate openers: "This method is responsible for…", "This class represents…", "Helper method
  for…", "It is important to note…". If nothing of substance is left without the opener, delete the
  whole block.
- `@param`/`@return` that repeat the parameter name or the return type.
- Workflow step comments that repeat the step's `name:`.
- Commented-out code.

## Keep, and tighten

A comment survives only if it explains a non-obvious rule, an architectural constraint, a
workaround for external behaviour, a performance, concurrency or security concern, a compatibility
requirement, deliberately surprising behaviour, or the reason for a decision that would otherwise be
reopened. In a shell script, every `|| true` keeps its reason.

Rewrite survivors as one or two sentences of plain technical English.

## JavaDoc that stays

Public types and their public methods: one short paragraph, two sentences at most. What it does, and
why it exists when the name does not say.

## Procedure

1. Read each file in full first: a reason often sits far from the code it explains.
2. Edit. When unsure whether a comment records a real constraint, keep it and raise it as a
   question.
3. Run the `verify` skill.
4. Report: files touched, comment lines removed, and each comment kept with the rule that kept it.
