# .claude/

Claude Code configuration for **this project**. Everything Claude adds for the
travel planner belongs here, not in `~/.claude/`.

```
memory/     project memory  ->  MEMORY.md is the index
briefs/     mission-control briefs (job / why / guardrails / done means)
plans/      implementation plans
commands/   slash commands  ->  /<filename-without-.md>
skills/     skills          ->  skills/<name>/SKILL.md
agents/     subagent definitions
```

`memory/` and `plans/` both override a default location under `~/.claude/`.
Memory in particular is no longer auto-recalled as a result, so read
`memory/MEMORY.md` at the start of a session.

`settings.json` is shared configuration; `settings.local.json` is personal and
should not be committed. Neither exists yet — add them only when there is
something to put in them.

Plan mode writes its file to `~/.claude/plans/` by default. Move it here when
the plan is settled.

Anything genuinely global — something useful across every repo on this machine —
still belongs in `~/.claude/`, but only when asked for explicitly.
