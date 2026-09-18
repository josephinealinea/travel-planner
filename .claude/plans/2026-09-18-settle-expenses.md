# Plan — Settle Expenses, and a mandatory payer on charged expenses

**Brief:** `.claude/briefs/2026-09-18-settle-expenses.md`
**Date:** 2026-09-18

Built test-first. Part 2 (the payer rule) lands before part 1 (the settle
section), because the settle computation is allowed to assume every charged row
names a payer, and writing it first would mean writing a defensive branch that
part 2 then makes dead.

---

## Step 1 — The payer rule, in tests first

`BudgetPaidByTest` currently asserts the opposite of what we now want in three
places. Rewrite, keeping the surviving cases untouched:

| Test | Was | Becomes |
|---|---|---|
| `anExpenseCreatedWithNoPayerHasNone` | charged create with no payer → saved with none | → **400**, renamed `aChargedExpenseMustNameWhoPaid` |
| `patchingWithAnEmptyPayerClearsIt` | `""` clears it | keep, but on a **pending** row; add `clearingThePayerOfAChargedRowIsRefused` |
| `aPlanEditCanChangeOrClearThePayerOfItsBudgetRow` | clear allowed | clearing allowed only while pending |

New cases:
- `aPendingExpenseNeedsNoPayer` — the field stays optional where money has not moved
- `tickingChargedOnAPayerlessRowIsRefused` — the second door
- `aChargedRowWithNoStoredPayerReadsAsItsCreator` — the legacy fallback, written
  as a hand-written YAML file the way `BudgetStatusTest` does, not as an object

## Step 2 — The payer rule, implemented

**`BudgetService`** — one private helper, called at the end of `create` and
`update`, after every field is set and before `budget.save`:

```java
private static void requirePayerWhenCharged(BudgetItem item) {
    if (item.isConfirmed() && isBlank(item.getPaidByUserId())) {
        throw ApiException.badRequest("Say who paid this.");
    }
}
```

Asking the *item* rather than the *input* is what covers all three doors at
once: create, tick, and clear all arrive here having already mutated the item.

**`BudgetSync.afterSave`** — the same call after it applies `charged` and
`paidByUserId`, so a plan's cost cannot open a fourth door. `ItineraryService`
already validates the payer before the plan is saved, so the ordering is right.

**The legacy fallback** — a method on `BudgetService` used when assembling the
summary, *not* a getter on `BudgetItem` (a derived getter serialises into the
YAML and then fails to read back — CLAUDE.md, Traps):

```java
static String effectivePayerOf(BudgetItem item) {
    String payer = item.getPaidByUserId();
    return isBlank(payer) ? item.getCreatedByUserId() : payer;
}
```

Only consulted for charged rows, and only by the settle computation and the
view. Nothing is written back.

## Step 3 — The settle computation, in tests first

New `SettleExpensesTest`, following `BudgetSharingTest`'s setup:

- `whatTheyOweYouAndWhatYouOweThemNetOut`
- `aPendingRowIsNotADebt`
- `thePayersOwnShareIsNotChargedToThem`
- `aRowSharedByNobodySplitsAcrossTheWholeTrip`
- `twoCurrenciesAreTwoLines`
- `threeWaySplitsToTheCentAndTheLinesAddUp` — 10.00 → 3.34/3.33/3.33
- `aRowYouNeitherPaidNorShareIsNotYours`
- `aPayerWhoHasLeftTheTripIsNotSettledWith`
- `aPublishedPageHasNoSettlements` — `summarise(trip)` with no viewer
- `settlementsAppearNowhereInAPublishedFile` — greps the rendered file, the way
  `PersonalPageTest` does

## Step 4 — The settle computation, implemented

In `BudgetService`, beside `breakdown`:

```java
public record Settlement(String otherUserId, String currency,
                         BigDecimal owesYou, BigDecimal youOwe,
                         BigDecimal net, List<Line> lines) {
    public record Line(String itemId, BigDecimal amount, boolean owedToYou) {}
}
```

added to `Summary` as a sixth component, `List<Settlement> settlements`.

Algorithm, over charged rows only:

1. payer `P = effectivePayerOf(item)`; skip unless `P` is a current member
2. sharers `S = members.sharersOf(item.getSharedByUserIds())`
3. if `P == me`: every `s in S, s != me` owes me `shareOf(amount, shared, s)`
4. else if `me in S`: I owe `P` `shareOf(amount, shared, me)`
5. accumulate into a map keyed by `(otherUserId, currency)`, collecting a `Line`
   per contributing row

`net = owesYou - youOwe`. Empty when `user == null`, exactly as `shares` is.
Sorted by member then currency so the table is stable between reloads.

A `Line` carries only `itemId`, `amount` and direction — the frontend already
holds every row in `items` and looks the rest up, so no field is duplicated.

## Step 5 — Frontend

**`js/pages/trip/budget.js`**
- `settlements` read from the summary; `settleRows` for the table
- `settleMemberName(userId)` reusing the existing member-name resolution,
  including the "Former member" fallback
- `openSettleDetails(row)` / `closeSettleDetails()` and `settleDetail` state
- `settleDetailLines` joining each `Line` to its item for description, date and
  category

**`trip.html`** — between the pager and `</section>` at the end of
`#panel-budget`:
- the section, its heading, and a one-line note that it counts charged
  expenses only
- the table, every cell carrying `data-label` for the phone card layout
- an empty state when there is nothing to settle
- the details dialog: `panel-switchable`, `x-dialog`, `aria-labelledby`

**`scss`** — the +/− colouring for the net column, through existing tokens
(`--tp-success` / `--tp-danger`), plus whatever the new section needs. No new
token, so every theme keeps working untouched.

**The three forms** — ticking "Expense already charged" with no payer selected
selects the current member; while it stays ticked, clicking the selected chip
does not empty the group. The API 400 remains the backstop.

## Step 6 — Verify

- `./gradlew test`
- `npm run css`
- In the browser: both directions on the LATAM trip, the details popup in both
  panel modes, the phone layout at 400px, and the three forms refusing to save
  a charged expense with no payer
- Publish the trip and grep the rendered file for a settlement figure

## Step 7 — Document

CLAUDE.md gains: the settle rules under the budget section, the mandatory-payer
rule beside the existing "Paid by" bullet, and the reversal noted so the older
"can be cleared" wording does not survive anywhere.
