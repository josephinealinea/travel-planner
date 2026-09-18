# Budget currency list and conversion

**Status:** done
**Date:** 2026-09-12
**Source:** `.claude/prompt/20260912-changes.md` item 5
**Plan:** `.claude/plans/2026-09-12-budget-currency-handling.md`

## Job
Limit "Show totals in" to currencies the trip actually uses, and source the
expense-entry currency list from configuration instead of a hardcoded array.

## Why
`CURRENCIES` in `js/format.js` is a fixed list of eleven codes offered on every
trip. Picking one the trip has no expenses or rate for is actively broken, not just
noisy: every row then fails conversion, so the page shows a total of **0.00** and a
missing-rate warning listing every currency in the trip.

## Guardrails
- Must not change: rates stay hand-maintained per trip. No live FX feed — that is a
  recorded decision in CLAUDE.md, and rates are part of the trip's stored data.
- Must not change: a currency with no rate is still reported in
  `currenciesMissingRates` and excluded from the total, never counted at 1:1.
- Constraint: the trip's current `displayCurrency` stays selectable even after the
  last expense in it is deleted, or the control would show a value absent from its
  own list.
- Out of scope: automatic rate fetching, historical rates, per-country subtotals.

## Open question — answered during planning
This note originally proposed deriving the expense-entry list from the trip's
destination countries via `CountryCatalog.currencyOf()`. **Superseded by explicit
instruction before implementation began:** the entry list instead comes from a new
per-user `currencies` list (seeded from `app.currencies.defaults`, freely editable
from Account), independent of any trip. `CountryCatalog` is untouched by this work.
This changes the "Done means" item below about where the entry list is derived
from — see its note.

## Done means
- [x] With expenses in EUR and USD only, "Show totals in" offers exactly EUR, USD,
      and the trip's current display currency — nothing else. Browser-verified:
      `budgetCurrencies` returned `["EUR","USD"]` with two matching expenses, then
      `["EUR","GBP","USD"]` after setting displayCurrency to GBP with zero GBP
      expenses (the "stays selectable" guardrail), then `["GBP","USD"]` after the
      EUR expense was deleted.
- [x] Selecting a budget currency converts each row by the trip's stored rate, and
      a currency with no rate is excluded from the total. Browser-verified: with no
      rate configured for USD, the total showed `0.00 GBP` and "Not included in the
      total — no exchange rate set for USD" — the pre-existing `BudgetService`
      conversion path is untouched by this work and still functions correctly
      against the new dropdown wiring.
- [x] **Reworded from the original wording, per the note above:** the expense-entry
      currency options derive from the signed-in user's own configured
      `currencies` list, not from trip data — this was an explicit instruction
      given mid-planning that superseded the brief's original "Open question"
      answer. The hardcoded list itself is still gone from `js/format.js`
      (`grep -rn "CURRENCIES" planner-web/js planner-web/*.html` → no matches).
      Browser-verified on a brand-new account: Plan-form, itinerary-entry,
      expense, and Edit-Trip currency dropdowns all offered exactly that user's
      three configured currencies, and Account's new "Currencies" card lets them
      add/remove codes with changes persisting across reload.
- [x] A currency with no rate still appears in the warning and is still excluded
      → see the conversion bullet above; unchanged code path, re-verified working.
- [x] `./gradlew test` passes → 37 tests, 0 failures, 0 errors across 7 classes.
