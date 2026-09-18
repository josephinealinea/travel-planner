# Config: popup or side panel

**Status:** done
**Date:** 2026-09-12
**Source:** `.claude/prompt/20260912-changes.md` item 2

## Job
Add a frontend setting that switches every tab's detail view between a centre popup
and a Checklist-style side panel, so both styles can be tried without editing markup.

## Why
The two styles are hardcoded per tab today — `trip.html` has 13 modal backdrops and
exactly 1 drawer — so comparing them means hand-editing the template. You have said
you cannot yet decide which you prefer; a setting turns that into a toggle and defers
the decision until there is something to judge.

## Guardrails
- Must not change: Escape-to-close and click-outside-to-close must work in **both**
  modes. They are wired per-element today and are easy to lose in the move.
- Must not change: the Checklist drawer's body stays permanently in the DOM. Putting
  it back inside `x-if` reintroduces the fixed bug where a `<select>` fed by `x-for`
  initialises before its options exist and silently shows the wrong value — see
  CLAUDE.md, Traps.
- Constraint: frontend only. No API change, and no new dependency.
- Out of scope: per-tab overrides — one setting governs all tabs — and syncing the
  choice across devices.

## Proposed shape
Follow the theme pattern: a localStorage-backed setting exposed in Account →
Appearance, read at page load. Same mechanism, same place people already look.

## Done means
- [ ] Set to popup: Destinations, Itinerary, Budget and Checklist all open centred
- [ ] Set to panel: all four open as a side panel
- [ ] Escape and click-outside close the panel in both modes, on all four tabs
- [ ] The Checklist detail still shows the correct Category and Destination in both
      modes when opened on an existing item
- [ ] The choice survives a reload
- [ ] `git diff planner-api` is empty
