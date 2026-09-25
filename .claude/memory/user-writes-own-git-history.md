---
name: user-writes-own-git-history
description: a hook blocks Claude from creating commits/branches/stashes; make file changes and tell the user the command
metadata:
  type: feedback
---

A PreToolUse hook (the user's git guardrail) blocks `git checkout -b`, `git commit`, `git stash …` and similar, with the message that the user writes their own git history. Read-only `git status`, `git diff`, `git archive` are fine.

**Why:** the user wants their history to be their own. It blocked even `git stash list` when it appeared inside a compound command, and the whole compound command (including unrelated steps) did not run.

**How to apply:** skills that say "commit after each task" or "never work on main" do not override this. Work in the tree, skip commit steps, and say so plainly at the end with the command the user can run. Keep git calls out of compound commands so a block cannot swallow other work. Related: [[git-workflow-preference]].
