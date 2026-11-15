# Contributing

Issues and pull requests are welcome. The application exists to demonstrate entity-metamodel, so a
change should make that demonstration clearer, more correct or more realistic.

## Before a pull request

1. `git config core.hooksPath .githooks` once per clone: the hooks refuse private paths, unlisted
   email addresses and assistant attribution in commit messages, and a push whose commits add a
   private path. `git push --no-verify` skips the push check; the Guards workflow then reports the
   path, but only after it has been published.
2. Change the contract first: `workspace-contract/src/main/openapi/workspace.yaml`, then the code.
3. `./mvnw -B verify` passes (Docker must run).
4. If you changed `deploy/compose/`: `deploy/compose/smoke-test.sh` passes.
5. If you changed a script or a workflow: `shellcheck -S style` and `actionlint` pass.

## Code style

- Reactive code: [docs/reactive-code-style.md](docs/reactive-code-style.md).
- Shell scripts and workflow `run:` blocks: [docs/shell-code-style.md](docs/shell-code-style.md).
- Comments explain a constraint or a reason the code cannot show; anything that restates the code is
  deleted. The full rule is in `CLAUDE.md`, which agents and people both follow.

## History

Pull requests are merged with **Rebase and merge**, so `main` is linear and every commit on a branch
lands on `main` as written.

- Branches: `feature/YYYY.MM_short-description`, from `main`.
- One logical change per commit, and each commit builds and passes `./mvnw -B verify` on its own.
  Fold review fixes into the commit they fix (`git commit --fixup=<sha>`, then
  `git rebase --autosquash origin/main`).
- Keep the branch current with `git rebase origin/main`, never by merging `main` in; the Guards
  workflow refuses merge commits. Push a rebased branch with `git push --force-with-lease`.
- Message: imperative subject of at most 72 characters, a blank line, then why, if the subject does
  not say it. No tool names, no generated-by lines, no attribution to an AI assistant.

## Working with Claude Code

The committed `.claude/` configures Claude Code for this repository:

- **Minimum version 2.1.295.** The hooks rely on `"onFailure": "block"`; in earlier versions a hook that
  fails or times out lets the command through.
- **Hooks:** `guard-bash.sh` runs before every Bash call and refuses sweeping `git add`, `--no-verify`,
  merges and plain force pushes. `commit-gate.sh` re-runs the private-path, email and shellcheck checks
  before `git commit`, and also before any command Claude Code cannot classify, such as a shell loop,
  so set `core.hooksPath` and your commit identity before the first session. Both read only their input, run only scripts of the
  checkout they live in, and write nothing outside it; changes to them must keep that rule.
- **Dependencies:** `guard-bash.sh` and `deploy/compose/smoke-test.sh` need `python3`. Without it on
  `PATH`, every Bash call of a session in this repository is blocked with `python3: command not found`.
  The commit gate runs `shellcheck` on staged scripts only when it is installed.
- **Skills** pre-approve `./mvnw`, `shellcheck`, `actionlint`, `docker compose` on
  `deploy/compose/compose.yaml`, and `deploy/compose/smoke-test.sh`, nothing else.
- **Attribution** is switched off in `.claude/settings.json`, so commits and pull requests made through
  Claude Code carry no attribution line.

"Only scripts of the checkout" is a safeguard for this repository and an exposure for any other. Run
`claude -p` over a fork or a pull-request branch, and that checkout's own hooks run, and its skills can
run its `pom.xml` and `compose.yaml` without asking. Before scripting over someone else's checkout:

- `--settings '{"disableAllHooks": true}'` turns hooks off for that run (setting it only in your user
  settings is not enough);
- `--setting-sources user` reads none of the checkout's settings files;
- `--bare` skips hooks and `CLAUDE.md` discovery, but skills still resolve by name, and it
  authenticates only with `ANTHROPIC_API_KEY` or an `apiKeyHelper`.

## Conventions

- Commit as `<id>+<user>@users.noreply.github.com`; no other address enters the repository unless it
  is in `.github/allowed-emails.txt`.
- No credentials, tokens or keys, ever.

By contributing you agree that your contribution is licensed under the Apache License 2.0, and you
follow the [code of conduct](CODE_OF_CONDUCT.md).
