# Settle Expenses, and a payer a charged expense cannot go without

**Status:** built and verified — uncommitted
**Date:** 2026-09-18
**Plan:** `.claude/plans/2026-09-18-settle-expenses.md`
**Follows:** `.claude/briefs/2026-09-17-paid-by-and-budget-pagination.md`, whose
"Out of scope: settle-up / who-owes-whom" this brief takes up, and one of whose
decisions it reverses (see Decisions).

## Job
1. A **Settle Expenses** section below the Budget tab's table: per other member
   and per currency, what they owe the signed-in member, what the signed-in
   member owes them, and the net. A **View details** button opens the rows
   behind one line, with a total.
2. Make the **payer mandatory on a charged expense**, in all three cost forms
   and at the API.

## Why
"Shared by" says whose money an expense is and "Paid by" says who put the card
down, but nothing adds those two up into the question a group actually asks at
the end of a trip: *what do I owe you?* Everything needed to answer it is
already stored; nothing was reading it together.

Part 2 exists because part 1 cannot be trusted without it. A charged expense
with no payer is money that has demonstrably left someone's hand with no record
of whose — it can appear in no settlement, so a settle table built over data
that allows it is quietly incomplete, in the one direction a reader cannot
detect.

## Decisions (agreed 2026-09-18)
- **Both directions and the net**, one line per (member, currency):
  `Travel Buddy`, `Owes you`, `You owe`, `Net`. The gross columns are what was
  asked for; the net is the number anyone actually settles on, and hiding it
  would make a reader do the subtraction.
- **No Currency column.** Every figure in the row already names its own
  currency, so a column repeating it four times said nothing the amounts did
  not. Two currencies with one member are still two rows — the buddy's name
  simply appears twice.
- **The net says its direction in words, not in a sign**: `(to receive) 450.00
  EUR` in green, `(to pay) 450.00 EUR` in red, and the amount is absolute
  because `(to pay) −450.00` states the same thing twice. A leading minus is
  the part of a figure a reader skims past, and words keep the column readable
  in monochrome and to anyone who does not separate the two hues — colour
  reinforces the direction rather than carrying it. The amount and its currency
  are joined by a non-breaking space so a phone card wraps after the direction
  instead of stranding "EUR" on its own line.
- **Per currency, never converted.** A debt is repaid in the currency it was
  incurred in, and a settle figure that had been through a rate table would be
  a number nobody can hand over. Two currencies with the same member are two
  lines.
- **Charged rows only.** A pending expense is money nobody has spent, so no one
  owes anything for it yet. Deliberately *not* wired to the panel's Group by
  selector: Forecast would otherwise produce debts for expenses that have not
  happened.
- **The payer is required when — and only when — the row is charged.** A
  pending row may still have none: a planned cost that nobody has paid would
  have to name a payer by guessing. This **reverses** the 2026-09-17 decision
  that a payer "can be cleared", which was written before anything read the
  field.
- **The rule is about the resulting state, not the request.** Creating a
  charged row with no payer, ticking "already charged" on a payer-less row, and
  clearing the payer of an already-charged row are three doors into the same
  bad state, and all three are refused.
- **A legacy charged row with no payer reads as paid by its creator.** Applied
  when assembling the view, never written back. Validating on read instead
  would make a hand-edited file fail to load, which is far worse than one row
  reading oddly. On this install it never fires — all 11 rows carry a payer.
- **The details popup is `panel-switchable`**, like the four tab detail views,
  so it honours Account → Appearance.
- **A new cost starts shared by the member entering it.** Found in testing: an
  expense added with "Shared by" left alone was split across the whole trip,
  so a solo flight turned up owed by five people. The *storage* rule is
  unchanged — an empty list still means the whole trip, which is what stops a
  stored row belonging to nobody — but the three forms now pre-select the
  member at the keyboard, exactly as they already did for "Paid by". Narrowing
  and widening are both deliberate acts now, and the hint under the field
  ("Pick nobody and it belongs to everyone on the trip") still describes the
  way out. Applied to all three forms rather than the budget's alone, or the
  same cost entered from a plan would divide differently from one typed into
  the budget.
- **"Everyone" is a chip of its own**, first in the Shared by group, and it is
  **the empty list rather than every name**. Naming every member would freeze
  today's membership into the row, so somebody joining later would be left out
  of a cost that is plainly the group's and somebody leaving would keep their
  name on it — the same reason the split is resolved per request instead of
  stored. It also makes the old implicit state legible: deselecting the last
  member used to mean "everyone" silently, and now lights the chip that says
  so.
- **Paid by comes before Shared by** on all three forms. Who handed the money
  over is the plain fact; whose money it is follows from it.
- **"Delete selected" moves up beside the tab's Add button** on Destinations,
  Checklist, Itinerary and Budget, replacing the row of its own it had under
  the filters (`.toolbar-actions`, now deleted along with its stylesheet rule).
  **Delete sits left, Add right**, so the primary action is the rightmost thing
  on the row on every tab and the destructive one is never what a thumb reaches
  first.
  This is **not** the 2026-09-17 review's dropped finding #12 coming back: that
  was about *hiding* the button until something is selected, which would shove
  the rows down under the pointer on the first tick. It still stays put and is
  still disabled rather than hidden — it has simply moved to a row that is
  always there.

## Guardrails
- **The settle figures must reconcile with the Budget tab to the cent.** They
  reuse `TripMembers.shareOf`, the one place that owns division and hands
  leftover cents to the earliest sharers. Dividing again in JS would drift.
- **Nothing reaches a published page.** `settlements` is empty with no
  signed-in member, the same rule `shares` follows, and `StaticSiteRenderer`
  never names it — including on a personal page, which *does* pass a viewer.
- Paid by still **changes no figure**: no total, share, forecast or country
  split moves because of who paid.
- `CONFIRMED`-by-default, `confirmedAt` stamped once and never moved, and every
  other `BudgetItem` rule in CLAUDE.md unchanged.
- A row the signed-in member neither paid nor shares contributes nothing.
- Out of scope: multi-party debt simplification (A→B→C collapsed into A→C),
  recording that a debt has been settled, settle-up on a published page.

## Done means
Verified 2026-09-18: `./gradlew test` (276 tests, 0 failures), `npm run css`,
and Chrome against the LATAM trip, Dark theme, restarted API.

- [x] `./gradlew test` passes: **276 tests, 0 failures**. `SettleExpensesTest`
      adds 10 and `BudgetPaidByTest` is up to 18. 28 fixtures across four other
      test classes needed a payer added — they create charged rows and were
      testing sharing, status and dates, not payers
- [x] Both directions net, per currency, and mirror from the other member's side
- [x] A pending row, and a row with no payer, produce no debt
- [x] The payer's own share is never charged to them
- [x] A row shared by nobody splits across the whole trip
- [x] Three-way 10.00 splits 3.34/3.33/3.33 and the lines add to the total shown
- [x] Creating, ticking and clearing all refuse to leave a charged row payer-less,
      as does a plan's charged cost — the four doors
- [x] The forms auto-select a payer when "already charged" is ticked and refuse
      to empty the group while it stays ticked; unticking restores clearing
- [x] `POST /budget` with `charged: true` and `paidByUserId: ""` answers
      **400 `Say who paid this.`** and writes nothing
- [x] The rendered published page contains no settlement — pinned by
      `noSettlementReachesAPublishedFile`, which greps a personal page too
- [x] The popup is named, takes focus, returns it to its opener, and docks
      right under Account → Appearance → side panels
- [x] Live figures independently recomputed from the YAML and matched exactly:
      rainer owes 655.00 EUR, is owed 205.00 EUR, net 450.00 EUR; plus 25.00 USD
- [x] At 400px the table becomes cards with every field visible and no sideways
      scroll, and the popup fits with nothing overflowing
- [x] Net reads `(to receive) …` green and `(to pay) …` red (measured from the
      rendered colours), in both the table and the popup's own Net row
- [x] A new expense opens with the member at the keyboard already selected under
      "Shared by"; editing an existing row still shows that row's real sharers,
      including a stored empty list, which is left empty rather than rewritten
- [x] With every row shared by one member alone the section shows "Nothing to
      settle" and hides the table entirely
- [x] All three forms order the fields Paid by → Shared by, lead the Shared by
      group with "Everyone", and carry the reworded hint. Everyone clears the
      names and lights up; clicking a member narrows to them and releases it;
      deselecting that last member returns to Everyone rather than to nothing
- [x] "Delete selected" sits left of Add on all four tabs, stays disabled at zero
      with its count hidden, enables on the first tick reading "(1)", and still
      opens its named confirm. Both buttons fit one row at 400px
- [x] CLAUDE.md documents both halves

## Found during the build, worth knowing
- **`table-cards` is an opt-in class**, like `panel-switchable` and `x-dialog`.
  The settle table was 605px in a 342px box at phone width until it was added —
  the same silent opt-in this file already warns about twice.
- **`Summary` is not what the frontend reads.** `TripViews.BudgetView` is a
  separate record and needed the field too; adding it to `Summary` alone left
  the section rendering with an empty table. That separation is also what keeps
  settlements off published pages, so it is working as intended.
- **The bulk-delete confirms pluralise correctly and always did.** "Remove 1
  expense?" was reported as reading "1 expenses" during this work — a false
  alarm from testing with `textContent`, which reads straight through the
  `x-show` that hides the "s". `innerText` respects it and shows the singular.
  This is the trap already in CLAUDE.md, tripped over while quoting it: when
  the question is what a reader sees, assert on `innerText`, `offsetParent` or
  a rendered box, never on `textContent`.
