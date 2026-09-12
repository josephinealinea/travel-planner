---
name: git-workflow-preference
description: User drives GitHub operations with plain git (push/pull/etc), not the gh CLI
metadata:
  type: feedback
---

For GitHub operations on this project, use plain git commands (`git remote
add`, `git push`, `git pull`, ...) — not the `gh` CLI.

**Why:** stated directly on 2026-09-12 when offered a choice between installing
`gh` and the manual git route. The user wants to drive repo setup and sync
themselves with commands they already know, not add a new CLI tool.

**How to apply:** when this project needs a GitHub action (create the remote,
push, pull, open a PR), give plain git commands and, for anything that isn't
plain git (like creating the repo itself), point to the github.com web UI
rather than reaching for `gh`.
