# Pending itinerary: design spec

Status: DRAFT for review. No open questions. No code written. After approval,
the next document is an implementation plan in `.claude/plans/`.

## Goal

An itinerary entry (or a checklist "Plan" booking) can be a **proposal**
rather than a settled plan — the same distinction Budget already has between
Pending and Confirmed money. Whoever creates an entry can leave it Pending
("suggested to the herd 🦙" rather than decided); other travel buddies see it
flagged, can 👍 it, and once every other participant has approved it — or the
creator says so directly — it becomes Final. A published page only ever shows
Final entries: nobody outside the planning conversation should see a proposal
as if it were the plan.

## Scope

In:
- `ItineraryItem.status` (`FINAL` default, `PENDING`) and
  `ItineraryItem.approvedByUserIds`, both YAML and Postgres/JDBC.
- A one-line V15 Flyway migration for the two new columns.
- `PATCH /itinerary/{id}/status` (creator-only, one-directional
  Pending→Final) and `POST`/`DELETE /itinerary/{id}/approve` (any resolved
  participant, toggles their own vote).
- `status` accepted on the existing create/update itinerary payload.
- A "This itinerary plan is Final" checkbox on the checklist Plan form and the
  itinerary Entry form, creator-only, defaulted checked.
- Final/Pending toggle chips in the Itinerary tab's Show toolbar.
- A Pending pill, "Suggested by / approved by N buddies" line, Approve/Undo
  button and creator-only quick-finalize checkbox on a Pending row.
- Excluding Pending entries from every published page (trip and personal).

Out (not v1):
- Any change to the Budget tab's own Pending/Confirmed status — unrelated
  field on a different domain object.
- Notifying anybody (email, toast-to-others) when an entry is approved or
  finalized. Everything here is pull (open the tab, see the pill), not push.
- A published-page preview of a Pending entry, even to the owner previewing
  their own publish request — the point of Final-only is that a proposal
  never has a public shape at all.

## Data model

### `ItineraryStatus`

```java
public enum ItineraryStatus { FINAL, PENDING }
```

Mirrors `BudgetStatus`/`CONFIRMED` exactly, for the same reason: every row
written before this field existed was somebody's actual plan, not a proposal,
so a record with no `status` in its YAML must read as `FINAL`. `FINAL` is
therefore the field's Java default (`private ItineraryStatus status =
ItineraryStatus.FINAL;`) and `setStatus(null)` coerces to `FINAL`, the same
null-safety `BudgetItem.setStatus` already has.

### `ItineraryItem.approvedByUserIds`

Ordered `List<String>` of member user ids, default empty, same shape as
`BudgetItem.sharedByUserIds` — order is who-clicked-first, kept so the hover
tooltip and the count are stable rather than resorted every read.

### Whole-stay semantics: one status, one approval list, per plan group

A stay is several `ItineraryItem` rows (the plan's own row plus one row per
later night, linked by `planId`). Status and approvals are conceptually
properties of *the booking*, not of one night, so a write to either always
applies to the **whole plan group**: the owning row (where `ownsItsPlan()` is
true) plus every row whose `planId` points at it.

`ItineraryService` gets:

```java
List<ItineraryItem> planGroupOf(ItineraryItem item)
```

— resolves the owning id (`item.ownsItsPlan() ? item.getId() : item.getPlanId()`)
and returns every row in the trip sharing it, exactly the population
`adoptOrphanedDays` and the plan-delete cascade already compute. Every status
write and every approve/unapprove goes through this: set the field on each
row in the group, save all of them, stamp `updatedAt`/`updatedByUserId` on
each (consistent with how a bulk unlink stamps every row it touches, not just
one). A single night with no `planId` and no later days is its own
one-element group, so the code has no special case for "not a stay".

## Storage

### Migration `V15__itinerary_status.sql`

```sql
ALTER TABLE itinerary_items ADD COLUMN status text;
ALTER TABLE itinerary_items ADD COLUMN approved_by_user_ids text[];
```

No `CHECK` constraint (matches the existing "enums as text" rule — adding a
third status later stays a one-place change).

### YAML

Plain fields on `ItineraryItem`, written the same way `sharedByUserIds` is:
`status` omitted only when Jackson never called the setter (old records);
`approvedByUserIds` an ordinary possibly-empty list.

### JDBC (`JdbcItineraryRepository`)

Add `"status"`, `"approved_by_user_ids"` to the column list, `parametersOf`,
and `mapRow`:

```java
values.put("status", JdbcValues.enumName(
    item.getStatus() == null ? ItineraryStatus.FINAL : item.getStatus()));
values.put("approved_by_user_ids", JdbcValues.textArray(item.getApprovedByUserIds()));
...
item.setStatus(JdbcValues.enumValue(rs, "status", ItineraryStatus.class, ItineraryStatus.FINAL));
item.setApprovedByUserIds(JdbcValues.textList(rs, "approved_by_user_ids"));
```

— the same `enumName`/`enumValue`/`textArray`/`textList` helpers
`JdbcBudgetRepository` already uses for `status`/`sharedByUserIds`.

### Contract and round-trip tests

- `ItineraryRepositoryContractTest` (YAML vs Postgres) gets cases for a
  Pending row, an approved-by list, and a plan group where status is set on
  a later day's id and expected to land on every row in the group.
- `EveryField`-style round-trip coverage gets both new fields, so a future
  field added without a column mapping fails the same way every other one
  does.

## API

### Create / update (`POST`/`PATCH /trips/{id}/itinerary...`)

Payload gains an optional `status` (`"FINAL"` / `"PENDING"`). Absent on a
PATCH leaves it alone (three-state rule, same as every other optional field
in this codebase); a POST with none defaults to `FINAL`. When the write
targets a plan's owning row, `ItineraryService` applies the resulting status
to the whole `planGroupOf` — so saving the Plan form with the checkbox
unchecked marks every night Pending together, even though only the owning
row's request carried the field.

### `PATCH /trips/{id}/itinerary/{itemId}/status`

Body: `{ "status": "FINAL" }`. Member-only, and only the group's creator
(`createdByUserId` of the owning row) may call it — 403 otherwise. Refuses
`PENDING` (`ApiException.badRequest`, `error.itinerary.statusOneDirectional`
or similar key): this endpoint is the list's quick "mark final" action, which
by design has no undo from the list — reversing to Pending is only available
by reopening Edit and unchecking the form's checkbox, an explicit act that
also lets the creator re-state why. Applies to the whole plan group.

### `POST` / `DELETE /trips/{id}/itinerary/{itemId}/approve`

Toggles the caller into/out of `approvedByUserIds` for the whole plan group.

- **Who may call it:** any user id in the group's *resolved* travellers (via
  `Travellers`, same resolution the picker and the sharers use — the whole
  trip when unset) — including the creator, since you asked for that.
  Anyone else gets 403.
- **Effect on `approve`:** adds the caller (no-op if already present, so a
  double-click is harmless), saves the group, then checks:
  *has every resolved participant other than the creator approved?* If yes
  and the group's status is currently `PENDING`, this call also transitions
  the whole group to `FINAL` through the same status-write path (so it
  stamps consistently with the quick-finalize endpoint). A group whose
  resolved participants are only the creator (a solo-buddy plan) can never
  reach this auto-transition through `approve` — only the creator's own
  checkbox/checkbox-in-form finalizes it. This is deliberate: requiring zero
  outside approvals to "count" would auto-finalize a solo plan the instant
  anyone opened the approve UI, defeating the purpose of leaving it Pending.
- **Effect on `unapprove`:** removes the caller; never itself reverts a
  `FINAL` group back to `PENDING` — once finalized (by either path), undoing
  an approval is just correcting the record of who was on board, not
  re-opening the proposal.

`ItineraryController` gets the two new routes; `ItineraryService` gets
`setStatus`, `approve`, `unapprove`, all resolving `planGroupOf` first.

## Publishing

`StaticSiteRenderer.render` (and the personal-page path, which shares the
same fetch) filters `tripItinerary` to `status == ItineraryStatus.FINAL`
**once**, at the point it currently reads
`var tripItinerary = itinerary.findAllOrdered(slug);` — before
`Travellers.of(...)` is computed and before either the trip page or any
member's personal page is assembled from it. This is the same "filter once,
every reader downstream sees the narrowed list" shape the personal-page
narrowing already uses, so a Pending entry cannot leak into a published file
through any of the paths that read `tripItinerary` afterward (weather day
list, budget's itinerary-linked rows, etc. are unaffected — they don't read
itinerary status).

`PersonalPageTest`/publish tests get a case: a Pending entry never appears in
either the trip page or a member's personal page, the same style of
whole-file search the existing privacy tests use.

## Frontend

### Forms — checklist Plan form and itinerary Entry form

A checkbox, "This itinerary plan is Final", defaulted checked, placed to the left of
the existing Cancel/Save row (`.row.row-end`) in both `planForm` and
`entryForm` — a new `.row-between` wrapper (label+hint on the left,
Cancel/Save unchanged on the right) rather than changing that row's own
layout.

Visibility:
- **New entry/plan:** always shown, checked.
- **Editing an entry/plan this user created** (the plan group's owning row's
  `createdByUserId === currentUserId`): shown, reflecting the group's current
  `status`.
- **Editing someone else's entry/plan:** not rendered at all.

Unchecked shows, directly under the checkbox: *"A proposed itinerary will be
suggested to the herd 🦙"* (italic, `field-hint`-styled, i18n key
`itinerary.proposedNote` / `checklist.proposedNote`).

`savePlan()`/`saveEntry()` send `status: checked ? 'FINAL' : 'PENDING'` only
when the checkbox is actually rendered for this user; otherwise the field is
omitted from the payload so an edit by a non-creator can never touch status.

### Itinerary tab — Show toolbar

Two more toggle chips beside the existing ALL/WEATHER/ITINERARY selector,
in the same toolbar row: **Final** (`itinShowFinal`, default `true`) and
**Pending** (`itinShowPending`, default `false`) — independent toggles, not
mutually exclusive with each other or with the ALL/WEATHER/ITINERARY
selector. Clicking **Weather Only** additionally, silently, resets them to
`true`/`false` (no toast — matches "no need to display any message").

`filteredItinerary` gains a second predicate alongside the existing category
filter:

```js
.filter((item) => item.status === 'PENDING' ? this.itinShowPending : this.itinShowFinal)
```

Both off yields an empty itinerary list, same as today's `itinShow ===
'WEATHER'` case — no special empty-state copy beyond the existing "nothing
matches those filters" line.

### Itinerary tab — Pending row

On a `PENDING` entry, right after the description (same row, same position
every theme already renders a pill in):

```html
<span class="chip chip-pending" data-i18n="itinerary.pending"></span>
```

Then, italic, under the meta line:

```
Suggested by {creatorName}[, approved by {n} buddy|buddies]
```

`{n}` counts everyone in `approvedByUserIds` (creator included, if they
clicked it too); a `title` attribute on that span lists their display names,
comma-joined, resolved the same way `paidByLabel` falls back to "Former
member" for someone who has since left. Plural via the existing
`key.one`/`key.other` i18n pattern (`itinerary.approvedByCount`).

**Approve button**, styled and labelled like `trip.recordPayment`
(`(👍🏻ᴗ_ᴗ)👍🏻`, hover title "Approve"): shown only to resolved participants of
that entry (via the same `Travellers`-backed resolution the picker uses,
computed client-side from `travellerNameList`'s underlying data — whole trip
when unset), and only while `status === 'PENDING'`. Once the current user is
in `approvedByUserIds`, the same button shows "Undo" instead (same
label-swap pattern as the settle table's Record payment → nothing / Delete
pair — here it's the same button flipping label and action, calling
`DELETE .../approve` instead of `POST`). Position: below the Edit button on
desktop (`.timeline-actions`, stacked); below the row's content on mobile,
same stacking rule the settle table's `table-cards-plain` already applies —
the itinerary timeline is div-based, not a `<table>`, so this needs its own
small `@media` rule in `_itinerary.scss` rather than reusing that class, but
the layout it produces is the same "actions drop beneath the card content"
shape.

**Creator-only quick-finalize checkbox**, to the left of a Pending row only
(no such checkbox on a Final row — mirrors the checklist's own status
checkbox, which also only makes sense on the state it can change). Ticking
it calls `PATCH .../status` with `FINAL` directly against the whole plan
group; per the one-directional API, there is nothing to un-tick. A short
inline confirmation note appears before the click is sent (same shape as the
checklist's `askComplete`/`confirmSetComplete` two-step, reusing that
pattern rather than inventing a new dialog): "Mark as Final? This will be
final for {the whole stay | this entry}."

## Testing

- Domain: `ItineraryStatus` default-is-FINAL round trip (mirrors
  `BudgetStatusTest`), `planGroupOf` resolution for a single entry vs. a
  multi-night stay.
- Service: `setStatus` creator-only + one-directional; `approve`/`unapprove`
  participant gating; auto-finalize triggers exactly when every non-creator
  resolved participant has approved, and never when the only resolved
  participant is the creator.
- Repository contract: YAML vs Postgres equivalence for both new fields,
  including a plan-group write landing on every row.
- Publish: a Pending entry is absent from both the trip page and every
  personal page (whole-file search, like the existing privacy tests).
- No frontend test suite exists in this repo; the form/list changes get
  manual verification in a browser per this project's own testing note.
