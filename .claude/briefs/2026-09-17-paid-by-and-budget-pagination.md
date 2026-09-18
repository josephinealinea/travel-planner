# Paid by, and budget table pagination

**Status:** done and verified end to end — uncommitted
**Date:** 2026-09-17
**Plan:** `.claude/plans/2026-09-17-paid-by-and-budget-pagination.md`

## Job
1. Record **who paid** an expense — one trip member — alongside the existing
   "Shared by", and show it in a **Paid by** column after Shared Amount.
2. **Paginate the Budget tab's table**, 20 rows per page by default, with the
   page size chosen in Account → Appearance, below Theme.

## Why
"Shared by" says whose money an expense is; nothing says who put the card down,
which is the first question when a group settles up. And a whole trip's
expenses in one table gets long.

## Decisions (agreed 2026-09-17)
- **Paid by starts as the member filling in the form**, is changeable to any
  member, and can be cleared. Rows with no payer — every row written before
  this — show "—".
- **Paid by is on all three cost forms**: the expense form, the checklist Plan
  form and the itinerary entry form, exactly where "Shared by" is. A plan's
  cost carries its payer onto the budget row it creates, as it already does its
  sharers.
- **The itinerary form matches the Plan form**: "Expense already charged",
  "Shared by" (with the Plan form's hint) and "Paid by", shown once a cost is
  entered. The user's answer asked for these "similar to checklist plan"; the
  first two already existed there, only behind a cost being typed, and the Plan
  form behaves the same way. Revisit if the intent was "always visible".
- **Not on published pages.** A payer is a member's identity; the published
  file never contains it (StaticSiteRenderer builds its payload by hand and
  never names the field).
- **Page size: 10 / 20 / 50 / 100, default 20, remembered in this browser**,
  like Theme and the panel style. It is a screen preference, not account data.
- A payer who has since **left the trip** reads "Former member": the planner
  only knows the names of current members, and the stored id is kept, not
  rewritten.

## Guardrails
- Never counted in any figure. Paid by changes no total, share, forecast or
  country split — those stay decided by "Shared by" alone.
- The pie, totals and legend still summarise **every** filtered row, not the
  visible page.
- Changing the Category filter returns to page 1; deleting rows never strands
  the table on an empty page past the end.
- Bulk selection spans pages ("Delete selected (3)" may include rows on other
  pages) — `selectedPresent` already intersects with every filtered row.
- `CONFIRMED`-by-default and every other BudgetItem rule in CLAUDE.md unchanged.
- Out of scope: settle-up / who-owes-whom, pagination on any other table.

## Done means
Verified 2026-09-17: API tests, plus Chrome (foreground tab, signed in, LATAM trip, nothing saved).

- [x] `./gradlew test` passes: 255 tests, 0 failures. `BudgetPaidByTest` adds 10: payer saved on create;
      none for null/""; patch null leaves it, "" clears it; non-member rejected; carried from a plan's cost;
      left alone on repricing; changed/cleared through a plan edit; changes no figure; survives YAML round trip
- [x] Expense, Plan and itinerary forms show Paid by, defaulting to me on a new cost; clicking me clears it,
      clicking Rainer selects only Rainer; Plan and itinerary show it only once a cost is entered
- [x] Itinerary form carries the Plan form's Shared by hint
- [x] Budget table: Paid by after Shared Amount; "—" on all 5 existing rows; `data-label` line on phone cards
- [x] `grep -rn paidBy` over publish code and assets finds nothing
- [x] Account → Appearance → "Budget rows per page" directly below Theme; 10/20/50/100; 20 selected by default
- [x] Paging (page size forced to 2 in memory): "Showing 1–2 of 5", Previous disabled; page 3 shows 1 row,
      Next disabled; Total unchanged at 751.73 EUR; Category filter returned to page 1; pager hidden at 20/page
- [x] CLAUDE.md documents Paid by and the page-size setting
- [x] The itinerary-edit freeze is an automation artefact: in a foreground tab, Edit on the costed Tallinn
      flight took 3 ms, opened with focus inside, and Escape closed it. The stalls were background-tab
      timer throttling (see CLAUDE.md, Traps)
- [x] **Saving a payer end to end** (verified 2026-09-18 against a restarted API, LATAM trip, Yellow fever
      vaccine row): setting rainer wrote `paidByUserId: 9d247c33…` to `budget/latam-trip-2026.yml` and the
      table read `rainer`; un-ticking him removed the key from the YAML entirely and the cell read `—`;
      the original payer was then restored. Through all three saves the share stayed 5.00 EUR and the
      charged total stayed 851.73 EUR, so the payer moved no figure. `confirmedAt` kept its 16-Sep stamp
      (never re-stamped) and `updatedByUserId` became the editor while `createdByUserId` stayed rainer.

## Found during verification, not fixed
- ~~`input[type="time"]` is missing from the styled input list in `scss/base/_forms.scss`.~~ **Fixed** after
  this brief was written — the selector is in the list and the Start/End time fields now render identically
  to the date fields (229×45, same border, padding and background). Re-verified 2026-09-18.
- `ItineraryService` validates "Shared by" only after the plan is saved, so a non-member sharer returns 400 but
  leaves the plan saved without its budget row. Pre-existing; Paid by avoids it by validating first.
  **Still open.**
