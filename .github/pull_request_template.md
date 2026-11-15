## What and why

<!-- One or two sentences: what changes, and the reason. -->

## How it was checked

- [ ] `./mvnw -B verify` passes
- [ ] If the contract changed: `workspace.yaml` was edited first, and the implementation follows it
- [ ] If the compose stack changed: `deploy/compose/smoke-test.sh` passes
- [ ] No credential, token, key or email address added
- [ ] Rebased onto `main`; each commit is one change that builds on its own
