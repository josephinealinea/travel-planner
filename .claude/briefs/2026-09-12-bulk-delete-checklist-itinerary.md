# Bulk delete on Checklist and Itinerary

**Status:** done — implemented and verified in browser 2026-09-12
**Date:** 2026-09-12
**Source:** `.claude/prompt/20260912-changes.md` items 3, 4
**Plan:** `.claude/plans/2026-09-12-bulk-delete-checklist-itinerary.md` (3 tasks, all executed)

## Job
Add row checkboxes and a single bulk-delete action to the Checklist and Itinerary
tabs, and remove the per-row Delete button from Itinerary.

## Why
Clearing leftover items today is one confirm dialog per row — a twenty-item tidy-up
is twenty dialogs. Itinerary is worse because every row carries its own Delete
button, so the column is mostly buttons nobody uses.

## Guardrails
- Must not change: the warning that a deleted checklist item's plans stay on the
  itinerary. It has to appear for bulk deletes too, not just single ones.
- Must not change: deleting an itinerary row that carries a cost still deletes its
  budget row (`BudgetSync.afterDelete`). Bulk must not bypass that path.
- Constraint: the Checklist drawer keeps its own Delete button — only Itinerary
  loses its per-row one.
- Out of scope: bulk edit, bulk status change, and bulk delete on Destinations,
  Budget or Members.

## Done means
- [x] Checklist: selecting N items and confirming removes exactly those N, and the
      confirm still states that their plans stay on the itinerary
- [x] Itinerary: no per-row Delete button remains; deletion is only via selection
- [x] Bulk-deleting an itinerary row that had a cost drops the Budget tab's row
      count by one and lowers the total accordingly
- [x] Selecting nothing leaves the delete action disabled rather than deleting all
- [x] `cd planner-api && ./gradlew test` passes
