# Plan — Record a payment to settle debts

**Date:** 2026-09-24
**Status:** approved and implemented 2026-09-24.
**Builds on:** `2026-09-24-simplify-debts.md` (the Settle panel, `Summary.balances`).

## Problem

Settle Expenses shows who owes whom but has no way to say "I paid you". Nothing
can ever reach zero. The obvious workaround — enter an expense "paid by Sam,
shared by Alex" — cancels the debt mathematically but is indistinguishable from
a real expense, so Alex's share is counted as spending: Total, category and
country slices, native totals and forecast all inflate, and the expense table
fills with repayments.

## Decisions (agreed)

1. **Who can record or delete a payment:** either party (the payer or the
   receiver) or the trip owner. The author is stored for the audit trail.
2. **No confirmation** from the receiver. Delete and re-record is the undo.
3. **Payments never reach a published page**, the trip's or a member's.

## Design

### A payment is its own record, not a kind of expense

`SettlementPayment` — the sixth per-trip entity. Deliberately **not** a `kind`
field on `BudgetItem`: every consumer of budget rows (the table, both
breakdowns, native totals, forecast, the other tabs, the publisher, the
importer's verifier) would have to remember to filter it out, and CLAUDE.md
already records what happens to the fifth door somebody forgets. As its own
record it is **excluded by construction** and the only code that reads it is the
settlement maths.

Fields: `id`, `tripId`, `fromUserId` (who handed the money over), `toUserId`,
`amount` (> 0), `currency`, `date`, `note`, plus the `Audited` fields
(`createdAt/By`, `updatedAt/By`).

### The maths

A payment is exactly a row "paid by `from`, shared by `to`" in the ledger, and
nothing else: `from` **+amount**, `to` **−amount**, per currency, never
converted. That is the whole change to the simplified plan — payments join the
whole-trip ledger before matching, so remaining payments shrink or vanish.

Pairwise mode (flag off) keeps its gross figures and adds what has been paid:
`Settlement` gains `paidYou` and `youPaid`, and
`net = owesYou − youOwe − paidYou + youPaid`. A pair that nets to exactly zero
stays listed as settled (as a zero-net pair already did) so a payment can be
seen and undone. Partial payment leaves the remainder; over-payment flips the
direction.

Same rules as expenses: a payment naming somebody no longer on the trip is
ignored (the trip does not know them), pending rows still do not exist here, and
the division is untouched — a payment is a whole amount between two people.

### Balance breakdown

`Balance.Line` gains a nullable `paymentId` beside `itemId` (exactly one is set),
so the balance dialog lists payments among the rows that add up to the net.

### API

- `POST /trips/{id}/settlements/payments` — `{fromUserId, toUserId, amount,
  currency, date?, note?}`. Refused (400) unless: both are current members, they
  differ, amount > 0, and **the caller is `from`, `to` or the owner**. Currency
  defaults to the trip's; date defaults to today.
- `DELETE /trips/{id}/settlements/payments/{paymentId}` — same permission.
- No `PATCH`: delete and re-record. (YAGNI.)
- Non-members get 404 as everywhere (`TripAccessService`).
- `TripViews.BudgetView` gains `payments` (member-only, like `settlements`).

### Where it is stored

- `SettlementPaymentRepository extends TripScopedRepository`; `Yaml…` (one list
  per trip, `travels/settlements/<slug>.yml`) and `Jdbc…` (table
  `settlement_payments`, keyed `(trip_id, id)`, `seq` identity, FK to `trips ON
  DELETE CASCADE`, `amount numeric`, no other FKs — same reasoning as every
  other cross-row link).
- Flyway `V10__settlement_payments.sql` — schema only.
- `BudgetService` gets the repository through a new `@Autowired` constructor;
  the existing 7- and 8-argument constructors stay and delegate with an empty
  repository, so the tests and callers that do not care are untouched.
- Deleting a trip removes the payments: a sixth file in
  `YamlTripRepository.delete`, `ON DELETE CASCADE` in Postgres
  (`everyTableReferencingTripsIsCovered` will demand the new table be named).
- Importer: read through the YAML repository, write through JDBC in file order;
  `ImportVerifier` compares them field by field; the report counts them.

### Never on a published page

`StaticSiteRenderer` builds `PublishedTrip` by hand and never names a payment.
Test greps the rendered trip page **and** a personal page for a payment's
amount and note.

### Frontend

- Each row of the payments table gets **Record payment** (both modes). It opens
  a dialog (`x-dialog`, `aria-labelledby`, `panel-switchable`) prefilled from
  the row: who pays whom (fixed), amount = the full net (editable, for a
  partial), currency (fixed), date = today, optional note. Save → `reload()`.
- A **Payments** list under the table, when any exist: date, "Sam paid Alex",
  amount, note, and a Delete button (confirm dialog like other deletes) shown to
  the parties and the owner.
- The balance dialog lists payments among its rows (simplified mode); the
  pairwise dialog gains a "Paid" line.
- Empty state: "All settled!" when nothing is owed, including when everything
  was paid off.
- Sticky header/footer, 44px targets on phones, direction in words — the
  conventions of the current settle dialogs.

## Tests (first)

`SettlementPaymentTest` (service, YAML store):
- a full payment zeroes a pairwise debt; a partial leaves the remainder;
  over-payment flips the direction
- simplified: a payment removes it from the plan and shrinks the rest;
  balances still sum to zero; every member sees the same plan
- **a payment changes no total** — Total, category, country, native totals and
  forecast identical with and without it (the whole reason for the design)
- permissions: payer, receiver and owner may record and delete; a third member
  may not; a non-member gets 404
- refusals: same person both ends, non-member either end, zero/negative amount
- currency independence; a departed party's payment is ignored
- author stored, `updatedBy` untouched on create (Audit rules)
- deleting the trip deletes its payments

Repository contract (`SettlementPaymentRepositoryContract`) run by a YAML and a
Postgres subclass; `EveryField` fixture; importer round-trip and verifier;
`PersonalPageTest`-style "no payment reaches a published file".

## Docs

CLAUDE.md: the payment record, "excluded by construction", the ledger rule, the
new table. README: a line under the Costs step.

## Out of scope

Editing a payment, receiver confirmation, payment methods/receipts, reminders,
recording a payment that is not against a listed row (a general "Record
payment" form), currency conversion.

## Outcome

Implemented as planned. Full API suite green with the container tests running
(none skipped). The frontend was exercised in a browser at desktop and phone
width, with simplify on and off: record a full and a part payment, delete one,
a paid-off pair reading Settled, and payments as rows in the balance dialog.

Deviations worth knowing:
- The Import path needed more than the plan listed — `YamlSource` (orphan
  detection), `ImportRunner`, `ImportVerifier.Store` (the settlement comparison
  now includes payments) and the constructors that build them.
- Three tests pin the latest Flyway version and the full migration list
  (`BaselineSchemaTest`, `DatabaseModeApplicationTest`, `DatabaseModeStartupTest`);
  each was moved to 10.
- The settle table now uses `table-cards-plain`: on a phone, two buttons per
  row squeezed the default card into a sliver. This also fixed the "Ne / t"
  label wrap the panel had before.
