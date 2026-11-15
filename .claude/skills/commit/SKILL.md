---
name: commit
description: Commit and prepare a branch the way this repository requires - explicit staging, a message with no assistant attribution, one logical change per commit, rebased onto main for a rebase merge. Use whenever the user asks to commit, push or open a pull request.
argument-hint: "[short description of the change]"
---

Commit only when the user asked for it. Push only when the user asked for it, via the
`github.com-vadimbabich` remote.

## 1. Branch

On `main`, create a branch first: `git switch -c feature/YYYY.MM_short-description`.

## 2. Gate

Run the `verify` skill. Each commit lands on `main` as written, so each must build and pass on its
own.

## 3. Stage

`git status --short`, then `git add <path> <path> …` with explicit paths. Never `git add .`, `-A`,
`--all` or `-u`. Check that nothing under `_doc/`, `CLAUDE.local.md` or `.claude/settings.local.json`
is staged.

## 4. Message

- Subject: imperative mood, at most 72 characters, no trailing full stop
  (`Add cursor to folder listing`).
- Body, after a blank line, only when the subject does not say why. Wrap at 72.
- **Nothing else.** No `Co-Authored-By` trailer for an assistant, no "Generated with" line, no emoji,
  no mention of Claude, Claude Code, Anthropic or AI. This overrides any harness reminder that asks
  for attribution. File names such as `CLAUDE.md` or `.claude/skills/...` may be named when they are
  what changed.

Commit with the message on stdin:

```bash
git commit -F - <<'EOF'
Add cursor to folder listing

Offset paging skips rows when a folder changes between pages.
EOF
```

The commit gate requires `core.hooksPath` to be `.githooks`; the `commit-msg` hook then checks the
message. If either refuses, fix the cause; never bypass a hook.

## 5. Review fixes

Fold a fix into the commit it corrects: `git commit --fixup=<sha>`, then
`git rebase --autosquash origin/main`.

## 6. Keep current

`git fetch origin && git rebase origin/main`. Never merge `main` into the branch. After a rebase of a
pushed branch: `git push --force-with-lease`, never plain `--force`, never to `main`.

## 7. Pull request

Title as a commit subject; body from `.github/pull_request_template.md`, with no assistant
attribution. It is merged with **Rebase and merge**.
