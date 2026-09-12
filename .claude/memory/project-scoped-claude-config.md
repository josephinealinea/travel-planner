---
name: project-scoped-claude-config
description: "In travel-planner only, add plans/skills/commands/agents to the project's .claude/, not ~/.claude/"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 30afbe20-f1fe-4bd6-a9ef-de7c42fb6cf6
  modified: 2026-09-12T09:09:11.960Z
---

In the **travel-planner project only**, every plan, skill, slash command,
subagent, settings file **and memory** goes in that project's `.claude/`
directory — not in `~/.claude/` — unless the user explicitly asks for it to be
global.

**Why:** asked for on 2026-09-12, then clarified the same day when the initial
confirmation read as a machine-wide default: the user wanted this scoped to
travel-planner, not applied to their other repositories. Their pre-existing
user-level commands and skills (add-trip, add-itinerary, new-post, update-trip
— which belong to the josephinealinea.github.io site) were deliberately left
where they are.

**How to apply:** in travel-planner, write to `.claude/memory/`,
`.claude/plans/`, `.claude/commands/`, `.claude/skills/<name>/SKILL.md`,
`.claude/agents/` or `.claude/settings.json`.

Two defaults point elsewhere and must be redirected by hand: plan mode writes
to `~/.claude/plans/`, and the harness auto-recalls memory from
`~/.claude/projects/…/memory/`. The consequence of moving memory here is that
it is **no longer injected automatically** — read `.claude/memory/MEMORY.md` at
the start of a session.

Do not infer anything from this about other projects, and do not edit
`~/.claude/CLAUDE.md`. The project's own [[CLAUDE.md]] is the authority.
