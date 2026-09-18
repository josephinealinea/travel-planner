# Lodging spans every day of the stay

**Status:** done
**Date:** 2026-09-12
**Source:** `.claude/prompt/20260912-changes.md` item 4
**Plan:** `.claude/plans/2026-09-12-lodging-spans-all-nights.md`

## Job
Make a lodging plan produce one itinerary entry per day of the stay instead of a
single entry on the check-in day.

## Why
A 24–27 Dec stay appears only on 24 Dec today, so the itinerary for 25 and 26 shows
no accommodation at all. On the published day-by-day page a reader cannot tell where
they are sleeping on those nights — which is most of what a trip page is for.

## Guardrails
- Must not change: **one** budget row per stay. `BudgetSync` currently creates a row
  per itinerary item; four entries must not become four budget rows and quadruple
  the trip total. This is the main hazard in the change.
- Must not change: non-lodging categories still produce exactly one entry.
- Constraint: `StaticSiteRenderer.days()` groups the published itinerary by day — the
  extra entries must land on the correct days there, not just in the app.
- Out of scope: editing one night independently of the others, and per-night pricing.

## Open question — answered during planning
**The check-out day gets an entry.** The source request settles it in its own
words: *"check in is 24.12 check out at 27.12, should create lodging itinerary for
24, 25, 26, and 27"*. So entry count is nights + 1, and "Plan 3N accommodation"
seeds four entries.

The two numbers disagreeing is real but does not read as an error, because the
days do not look alike: check-in shows 15:00, check-out shows 11:00, and the
nights between show no time at all. The 3N still counts nights, which is what it
says.

## Done means
- [x] A lodging plan 24 Dec → 27 Dec produces itinerary entries on 24, 25, 26 and 27.
      Verified on the real Christmas Trip 2026: the Gent stay (25 Dec 15:00 → 27 Dec
      11:00) lists on 25, 26 and 27, and the Luxembourg stay (24 → 25) on both its
      days, in the app and on the published page alike. Asserted by
      `StaticSiteRendererTest.aStayAppearsOnEveryDayFromCheckInToCheckOut`.
- [x] The trip budget total is identical before and after that plan spans four days.
      True by construction rather than by care: the span is derived at display time,
      a stay is still one stored `ItineraryItem`, and no budget code was touched.
      Measured on the same trip — 181.11, 4 rows, before and after. The final review
      confirmed `BudgetSync` cannot see days at all, so extra rows are structurally
      impossible from a display change.
- [x] `./gradlew test` passes, including a new test asserting the entry count for a
      multi-night stay — 46 tests across 8 classes, 0 failures, force-rerun. Three new
      tests: the day span, the cost/times placement, and a guard that a non-lodging
      item which crosses midnight still belongs to one day.
- [x] The published page shows the lodging on each of those four days — verified in
      the embedded `window.TRIP` JSON and rendered in the browser.

## Beyond the brief
The cost is shown on the check-in day only. Repeating it on each day would have read
as four charges and contradicted the single budget row this brief protects — the same
hazard as the guardrail, one layer up.
