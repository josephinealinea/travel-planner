# Travellers: who is going to which part of a trip

Status: implemented 19 Sep 2026, following `2026-09-19-trip-travellers.md`.
Two decisions were made during the build and are recorded below: the cost
default (section 4) and the owner-only switch (Decisions).

## The problem

A trip is planned by several travel buddies, and not all of them go
everywhere. One friend only joins for the Cusco leg; two others skip Uyuni. The
Budget tab already copes with this, because each expense names who shares it
(`sharedByUserIds`) and the tab shows each buddy only their rows. Destinations,
the checklist and the itinerary do not: everyone sees everything, so a buddy's
screen is full of plans that are not theirs.

## Decisions (made with the user, in this order)

| Question | Decision |
|---|---|
| What do buddies not on something experience? | **Focus view.** Each buddy sees *Mine* by default and can switch to *Whole trip*. Nothing is secret: the server still sends every row to every member, exactly as Budget does today. |
| How is "who is on it" decided? | **Copied down** the chain: destination → checklist item → plan → a plan's cost. Each level can be edited on its own. |
| What happens when a parent's list changes later? | **Children follow unless edited.** A child nobody has edited follows its parent. A child someone edited keeps its own list. **Costs never follow**: moving money between buddies silently is not acceptable. |
| Published pages | **The trip page stays whole; a personal page shows only that buddy's parts.** |
| The switch | **One switch in the trip header**, remembered per browser. It covers Overview, Destinations, Checklist and Itinerary including weather rows. **Budget is unchanged**, always the buddy's own share. |
| Who gets the switch (decided after Task 8) | **Only the trip's owner.** Every other buddy is always on Mine, with no "N more on the whole trip" hint and a toast saying something they saved for others "won't show here". Still focus view, not privacy. |

Implementation approach, chosen over the alternative: **inherit unless set**
(below), rather than copying lists into children and rewriting them when a
parent changes. Inheriting gives "follow unless edited" with no bulk rewrites,
no audit-trail ambiguity about who "edited" a child that a cascade rewrote,
and no data conversion for existing trips.

## 1. What is stored

A new optional field, **`travellerIds`** (`List<String>`, trip-member user ids,
ordered, de-duplicated), on:

- `destinations/domain/Destination`
- `checklist/domain/ChecklistItem`
- `itinerary/domain/ItineraryItem`

`BudgetItem` is **not** changed; it keeps `sharedByUserIds`.

The field has **three states**, and the difference between the first two is
the whole design:

| Stored | Meaning |
|---|---|
| absent / `null` | **Not set: same as the parent.** |
| `[]` | **Explicitly the whole trip.** |
| `[ids…]` | Just those buddies. |

- **YAML**: `null` is absent (NON_NULL), `[]` is written as `travellerIds: []`.
  Every existing record has the field absent, so every existing trip resolves
  to the whole trip and looks exactly as it does today.
- **Postgres**: `V2__travellers.sql` adds a **nullable** `traveller_ids text[]`
  to `destinations`, `checklist_items` and `itinerary_items`, with **no
  default**. `NULL` means not set and `'{}'` means the whole trip. This is
  deliberately unlike `budget_items.shared_by_user_ids` (`NOT NULL DEFAULT
  '{}'`), which has only two states. The JDBC mappers must write `NULL` for a
  null list and must not collapse it to `'{}'`, and the reverse on read. The
  migration is schema-only, as `MigrationsAreSchemaOnlyTest` requires.

## 2. Resolving a record's travellers

One resolver on the server, **`trips/api/Travellers`**, beside `TripMembers`
and built the same way: a static record constructed from the trip and its
per-trip lists, with no repository calls of its own. It answers "who is on
this?" for any destination, checklist item or itinerary row, and "is this
mine?" for a given user.

Resolution, in order:

1. **Destination**: its own list if set, otherwise the whole trip.
2. **Checklist item**: its own list if set. Otherwise, if
   `seededFromDestinationId` names a destination that **still exists**, that
   destination's resolved list. Otherwise the whole trip.
3. **Itinerary row that is a plan** (no `planId`): its own list if set.
   Otherwise, if `checklistItemId` names an item that still exists, that
   item's resolved list. Otherwise the whole trip.
4. **Itinerary row that is a later day of a plan** (`planId` set): **always
   its plan's resolved list**, ignoring any list of its own. A 4-night booking
   cannot be half yours. If the plan row is gone, `adoptOrphanedDays` has
   already promoted a survivor, so this only fails to resolve on a hand-edited
   file, where it falls back to the whole trip.

Then, for every result:

- **Buddies who have left the trip drop out**, keeping order, with names not
  rewritten. Re-adding them restores them, the same as Budget's split.
- **An empty result means the whole trip.** This is the same safety property
  `TripMembers` states: a record whose named travellers have all left must not
  become visible to nobody.

"Mine" for user *u*: the resolved list is the whole trip, or contains *u*.

**Deleting a destination** leaves its checklist items' `seededFromDestinationId`
exactly as `DestinationService` does today. Items that were following it fall
through rule 2 to the whole trip. Losing a place never hides the planning done
against it, the same principle as unlinking rather than deleting.

## 3. What the API sends

`TripViews` gains, for the signed-in member only, a **`mine`** block:

```
mine: { destinationIds: [...], checklistItemIds: [...], itineraryItemIds: [...] }
```

- This is the same pattern as `budget.shares`: **the server decides, the page
  filters**. The browser never re-implements the resolution, so the planner and
  a personal published page cannot disagree.
- The raw `travellerIds` fields still reach the page through the unwrapped
  domain objects. The forms need them to tell "following" from "set".
- **Resolved lists for display** (the 👥 chips and the "Same as Cusco: Maia,
  Joey" hint) are also sent, as `travellers: { destinations: {id: [ids]},
  checklist: {…}, itinerary: {…} }`, **only for records whose resolved list is
  not the whole trip**, to keep the bundle small.
- **My Trips cards**: `checklistTotal` and `checklistCompleted` count **the
  signed-in member's** items, computed with the same resolver.

## 4. Writing it

Create and update requests for destinations, checklist items and itinerary
entries accept:

| Field | Meaning |
|---|---|
| `travellerIds` absent or `null` | Leave it alone. On create, leave it unset, i.e. follow the parent. |
| `travellerIds: []` | Explicitly the whole trip |
| `travellerIds: [ids…]` | Those buddies, validated by `TripMembers.validate`, which rejects anyone not on the trip |
| `inheritTravellers: true` | Clear back to not set, so it follows the parent again. It wins over `travellerIds` if both are sent. |

The explicit flag exists because the API cannot tell "sent `null`" from "did
not send it" in these request records. The payer field's three-answer
convention (`""` clears) does not fit a list.

- **Later days of a plan** reject `travellerIds` and `inheritTravellers` with a
  400 ("follows its booking"), rather than storing a value rule 4 would
  ignore.
- **Seeding** (`ChecklistSeeder`, and `DestinationService`'s later lodging
  item) stores nothing, so seeded items follow their destination.
- **Spreading a stay** (`ItineraryService.spreadPlanOverItsDays`) stores
  nothing on the later days.
- **A plan's cost** (decided with the user during Task 8, replacing the first
  wording): a new cost starts shared by the plan's travellers **only when the
  plan is for particular buddies**. For a whole-trip plan it keeps the default
  every cost had before this feature, which is just the member entering it,
  so ordinary plans behave exactly as they did. The forms apply this rule and
  always send what they show. On the API, a request that sends **no**
  `costSharedByUserIds` gets the plan's travellers when the plan is limited,
  and is left unset otherwise, as before. After that the row's
  `sharedByUserIds` is a fixed list like any other, and later changes to the
  plan's travellers never touch it. An explicitly sent `costSharedByUserIds`,
  including `[]`, is used as sent.
- **Auditing**: setting or clearing `travellerIds` is an ordinary edit, stamped
  with `Audit.touched` by the service that made it. Following a parent involves
  no write at all, so there is nothing to stamp.

## 5. The screens

### The picker: "Who's going"

This reuses `member-picker.js`'s pills. It gets a third mode beside the two
Shared by already has.

| Form | Default | Choices |
|---|---|---|
| Destination | **Everyone** | Everyone, or pick buddies |
| Checklist item (create form and drawer) | **Same as Cusco**, or **Same as the trip** when it has no destination | Keep following, or **Choose…** (starts from the inherited names), or **Back to Cusco's** |
| Plan (checklist Plan form, Itinerary add/edit) | **Same as its checklist item** | As above |
| A later day of a stay | No picker, just "Follows the booking" | none |
| Plan cost "Shared by" | The plan's travellers if it is for particular buddies, otherwise just you, **when the cost is first added** | Editable as today |

While following, the picker shows what it would be leaving, in muted text:
"Same as Cusco: Maia, Joey".

### Rows and cards

A **👥 Maia, Joey** chip on destination rows, checklist rows and itinerary
entries, **only when the resolved list is not the whole trip**. Names are
display names resolved from the current member list. A buddy who has left is
already dropped by the resolver, so no "Former travel buddy" appears here.

### The Mine / Whole trip switch

- Two toggle buttons (`aria-pressed`) in the trip header's meta row, beside
  the dates and the buddy count.
- A new module `js/trip-scope.js` stores the choice in `localStorage` under
  `tripScope`, defaults to `mine`, and wraps every access in try/catch, like
  `panel-mode.js`.

| Tab | In Mine |
|---|---|
| Overview | "Destinations", "Checklist done" and "Itinerary entries" count yours. "Up next" and "Needs planning" list yours. "Travel cost" is Budget and is unchanged. |
| Destinations | Only yours. The per-city checklist count counts your items. |
| Checklist | Only yours, and the progress bar measures only yours |
| Itinerary | Only your plans and days. Weather rows only for your destinations. A day with nothing of yours on it is hidden. |
| Tab badges | Count what is shown |
| Travel Buddies, Budget, Publish | Not affected |

Rules:

- **Nothing silently vanishes.** While Mine hides anything, the filtered tab
  shows one line: "Showing your part · 2 more destinations on the whole trip ·
  Show whole trip".
- **Saving something that does not include you** says so in its toast: "Saved.
  It's not on your list, so it shows under Whole trip."
- **Bulk selection**: switching to Mine unticks rows that just left the view,
  and "Delete selected" only ever acts on visible rows. No deleting rows you
  cannot see.
- **Link pickers stay whole**, for example choosing a checklist item for a
  plan, or the destination dropdowns. Linking is not viewing.
- The weather lookup is unchanged. It still fetches for every destination,
  once per trip, and only the display filters. Otherwise switching would
  trigger a lookup, and the one-call-per-trip rule would be at the mercy of a
  view setting.

## 6. Published pages

- **The trip page** (`/p/<slug>`) is unchanged: whole trip. **No traveller
  names or ids are ever written into a published file.** `StaticSiteRenderer`
  builds `PublishedTrip` by hand and never names the field.
- **A personal page** (`/p/<slug>/m/<buddy>`) is rendered for its viewer
  already. It now also filters destinations, checklist and itinerary days to
  that viewer's "mine", and the header's flags and route and the weather
  places follow their destinations. Per "not displayed means not shipped",
  **the other buddies' parts are left out of the file**, not hidden in
  `page.js`.
- The personal page is a snapshot like everything else published: a change
  shows on the next publish.

## 7. Deliberately out of scope

- Privacy, i.e. the server withholding rows. This design adds nothing that
  would stop it being built later, on the same resolver.
- A whole-trip view of Budget, or per-tab switches.
- Notifications ("you've been added to Cusco").
- Hiding trips from My Trips. Every buddy still sees every trip they are on.
- Travellers on manual budget rows beyond the existing Shared by.

## 8. Testing

**Server (JUnit):**

- `TravellersTest`, the resolver in isolation: each rule in section 2;
  not-set versus `[]`; a left buddy dropping out; all named buddies left means
  the whole trip; a deleted destination falls through; a later day follows its
  plan even with its own list set; order preserved.
- Service tests, one per module: seeding and spreading store nothing; the
  three request states plus `inheritTravellers`; non-members rejected (400);
  a later day refuses the fields; audit stamping on set and clear.
- `BudgetSync`: a first cost with no sharers sent takes the plan's travellers;
  sent `[]` stays whole trip; changing the plan's travellers later leaves the
  budget row alone.
- `TripViewAssembler`: `mine` and `travellers` per signed-in member; My Trips
  counts are per member.
- Storage: every repository contract test (YAML and Postgres) round-trips
  **null and `[]` as different values**, since this is the one place the
  feature can break silently. `EveryField` fixtures set the field;
  `MigrationsAreSchemaOnlyTest` covers V2; `ImportVerifier` compares it and
  `YamlImporterTest` imports it.
- `PersonalPageTest`: a destination for another buddy only is absent from the
  whole personal file, and present on the trip page. No member id appears in
  either file.
- Every run reports **0 skipped** container tests.

**Browser** (demo API on scratch data, never the real `data/`):

- The switch in both states, and remembered across reloads.
- The "N more on the whole trip" line and its link.
- Unticking on switch.
- Each form's picker, including Choose… and "Back to Cusco's".
- A later day's "Follows the booking".
- A plan's first cost prefilled.
- A personal page against the trip page.
- Phone width.

## 9. Documentation

- `CLAUDE.md`: a new rule under *The cascade* for "travellers inherit, costs
  copy once". It should cover the three states, the resolver as the one place,
  and why `traveller_ids` is nullable when `shared_by_user_ids` is not.
- `README.md`: one line in *How a trip comes together*.
