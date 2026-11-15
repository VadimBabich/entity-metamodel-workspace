# Shell code style

Rules for every shell script here: the guards in `.github/scripts/`, the git hooks in `.githooks/`,
the Claude Code hooks in `.claude/hooks/`, `deploy/compose/smoke-test.sh`, and inline `run:` blocks
in `.github/workflows/`. Adopted from
[entity-metamodel](https://github.com/VadimBabich/entity-metamodel), where each rule in §1 and §2
records a defect that shipped and was caught.

**Core principle.** Readability and operational safety over cleverness. These scripts run
unattended and are read while something is broken.

---

## 1. Prologue and interpreter

- **`#!/usr/bin/env bash` and `set -euo pipefail`.**
- **Never add `-E`.** `-E` carries the `ERR` trap into command substitutions, where a trap ending in
  `exit 1` **overwrites the subshell's real status**. In entity-metamodel's guards that turned
  `grep`'s exit 2 (broken pattern) into exit 1 (no match) and silently disarmed a fail-closed
  branch. If you add `-E`, prove that every `$( )` still reports the status its caller branches on.
- **Bash 3.2.** macOS ships 3.2.57. Arrays, `[[ ]]`, `<<<` and `+=` work; `${var,,}`,
  `mapfile`/`readarray` and associative arrays do not. Use `tr '[:upper:]' '[:lower:]'` and a
  `while read` loop.
- **`readonly` constants** at the top, before any function.
- **Quote every expansion**: `"${var}"`. No reliance on word splitting or globbing, no `eval`.

## 2. Exit status is what goes wrong

- **A pipeline reports only its last stage.** `value="$(git log … | sort -u)"` yields `sort`'s
  status, so a failing `git` looks like empty output. Capture first, then transform:

  ```bash
  if ! log_output="$(git log --format='%ae' "${range}")"; then
    log_error "reading authorship for '${range}' failed"
    return 1
  fi

  authors="$(printf '%s\n' "${log_output}" | sort -u)"
  ```

- **A failing substitution inside an argument does not trip `set -e`.** `scan "$(extract …)"`
  discards `extract`'s status. Assign to a checked variable first.
- **`while read` at the end of a pipeline runs in a subshell**: what it accumulates is lost. Feed
  the loop with a here-string or process substitution.
- **Tell "nothing found" from "the tool broke".** `grep` exits 1 on no match and 2 or more on error.
  Tolerate 1 explicitly; never blanket a pipeline with `|| true`:

  ```bash
  matches="$(printf '%s\n' "${text}" | grep -oE "${PATTERN}")" || grep_status=$?

  if [ "${grep_status}" -gt 1 ]; then
    log_error "extraction failed (grep exit ${grep_status})"
    return 1
  fi
  ```

**Fail closed on empty input.** A check that examined nothing has not passed. Confirm the input
exists and is non-empty before reporting success; if nothing to examine is a legitimate outcome, say
so with a `WARN`.

## 3. Traps

- Add a trap only when there is something to clean up, such as a temporary file. Without `-E` a
  trap covers top-level commands only.
- Call `main` so that handled failures do not trip it:

  ```bash
  if ! main "$@"; then
    exit 1
  fi
  ```

## 4. Structure

- Order: **prologue → constants → logging → helpers → orchestration → `main`**, blank lines between
  steps.
- One responsibility per function, `local` for every variable, no flag parameters choosing between
  behaviours: write two functions.
- Early returns over nesting.
- When a helper's non-zero status would mean "not applicable" rather than "failed", print nothing
  and return 0; the caller tests for empty output.
- Logic longer than a few lines leaves the workflow YAML for a script under `.github/scripts/`,
  where it can be run and tested locally.

## 5. Control flow

```bash
if ! check_service; then
  log_error "service unavailable"
  exit 1
fi

process_data
```

Never `check_service && process_data || exit 1`. `||` is for capturing a status (`… || status=$?`)
or a tolerated failure, and **every `|| true` states its reason** at the call site.

## 6. Naming

Names say what the value is: `deadline_epoch`, `staged_scripts`, `access_token`. Never `data`,
`tmp`, `result`, `out`, `x`.

## 7. Logging

- Errors and warnings go to **stderr**; only results go to stdout.
- **Say when coverage shrank.** A step that examined less than it normally would logs a `WARN`
  naming what went unexamined.
- **Never log a secret**, not even a fragment. The smoke test's token stays in a variable.
- An error names what was attempted, on what, and what to do next.

## 8. Tests and the gate

- **`shellcheck -S style`** passes on every script before commit; the commit gate and the Guards
  workflow both run it.
- **Assert stderr as well as the exit code.** A guard that exits 0 while printing `ERROR` is
  broken.
- **Test both directions**: a positive control (it catches the bad case), a negative control (it
  passes the good case), and the fail-closed paths (empty input, missing arguments).
- **A guard must accept the commit that introduces it.**
- Run tests that need real commits in a scratch repository outside the working tree, and leave
  nothing behind.

## 9. Footprint

Guards and hooks are read-only inspectors, safe to re-run: no changes to the working tree, no files
left behind. A script that must change state (the smoke test starts the stack and leaves it
running) says so in its header and reaches the same end state on every run.
