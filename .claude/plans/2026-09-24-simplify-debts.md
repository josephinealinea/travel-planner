# Plan — Trip debt simplification (config flag, Splitwise-style)

**Date:** 2026-09-24
**Status:** approved by the user, implementing test-first.

## Goal

A backend config, **off by default**, that changes how Settle Expenses works.
Off: today's behaviour, pairwise debts from the signed-in member's side. On:
debts are simplified across the whole trip so fewer payments are needed
(A owes B, B owes C → A pays C).

## Decisions (agreed)

1. **Install-wide flag**, not per trip: `app.settlement.simplify-debts`, env
   `SIMPLIFY_DEBTS`, default `false`.
2. **View details stays when ON**, but shows the signed-in member's **balance
   breakdown** in that currency rather than the rows behind one payment (a
   simplified payment is not caused by any one row).
3. A member may be told to pay somebody they never shared an expense with —
   that is the point of simplifying.
4. A member with a zero balance has no payment row and sees nothing; the
   existing "All settled" message covers it.

## Backend

**Config.** `SettlementProperties(boolean simplifyDebts)` — a record of its own
under `app.settlement`, registered on its own config class, never on
`AppProperties` (constructed positionally in a score of tests).

**Constructor.** `BudgetService` gets a new `@Autowired` constructor taking the
properties. The existing 7-arg constructor stays and delegates with `false`, so
the 13 tests and `ImportVerifier` that construct it are untouched and flag-off
behaviour is identical by construction. (Two constructors need `@Autowired` on
the real one — see CLAUDE.md Traps.)

**Algorithm (ON).**
- Whole-trip net balance per member per currency: for each charged row whose
  payer is a current member, payer `+amount`, each sharer `−shareOf`. The
  payer's own share cancels. Balances sum to exactly 0 per currency because
  `TripMembers.shareOf` hands out the odd cent.
- Greedy matching: largest creditor with largest debtor, settle `min(a, b)`,
  repeat. At most n−1 payments per currency. Ties broken by user id so the plan
  is **independent of who is looking** — every member computes the same plan.
- Each member sees only payments they are part of, in the existing
  `Settlement` shape (`owesYou` / `youOwe` / `net`), with `lines` empty.
- Unchanged rules: charged rows only, per currency never converted, departed
  payers skipped, nobody owes themselves.

**Balance breakdown.** New `Balance(currency, net, lines)` per currency, exposed
as `Summary.balances` and mirrored on `TripViews.BudgetView`. Lines are the rows
you paid for (`amount − your share`) and rows you share that someone else paid
(`−your share`); rows netting to zero for you are omitted. Empty when the flag
is off and on published pages, like `settlements`.

## Frontend (small)

The details button opens the balance modal instead of the row list when
simplify is on; title says "balance", the settle note explains the
simplification. Driven by a `settlementsSimplified` boolean on the budget view.

## Tests (first)

New `SettleSimplifiedTest`:
- chain A→B→C collapses to one payment
- 3-cycle nets to nothing
- currencies independent
- balances sum to zero, including a three-way odd cent
- payments ≤ n−1
- every member sees one consistent plan
- pending rows ignored; departed payer ignored
- balance lines sum to net, and net equals the sum of the member's payments
- zero-net rows omitted from the breakdown
- flag off: `settlements` unchanged, `balances` empty
- no balance reaches a published file

`SettleExpensesTest` stays as the flag-off proof.

## Docs

Update the Settle Expenses section of `CLAUDE.md`; mention `SIMPLIFY_DEBTS` in
the README. (Deviation: `docs/deploy.md` lists no comparable flags such as
`REQUIRE_OWNER_APPROVAL`, so the README is where the sibling flag is
documented and this one follows it.)

## Outcome

Implemented 2026-09-24. Full suite: 751 tests, 0 skipped, 0 failures. The
frontend changes (`budget.js`, `trip.html`) are syntax-checked but were not
exercised in a browser.
