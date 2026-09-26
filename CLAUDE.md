# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Two independent projects: `planner-api` (Spring Boot 3.5 / Java 21; YAML files
locally, PostgreSQL behind `feature-enable-database`)
and `planner-web` (static pages, Alpine.js, Sass). The [root README](README.md)
covers product behaviour; this file covers what is hard to see from any single
file.

## Where new configuration goes

**Everything you add — plans, skills, slash commands, subagents, settings —
goes in this project's `.claude/`, never in `~/.claude/`, unless the user
explicitly asks for it to be global.**

This applies to **travel-planner only**. It is not a statement about how the
user works elsewhere, so do not carry it into another repository or into
`~/.claude/`.

```
.claude/memory/     project memory  ->  MEMORY.md is the index
.claude/briefs/     mission-control briefs (job / why / guardrails / done means)
.claude/specs/      design specs (what and why; approved before a plan is written)
.claude/plans/      implementation plans (order and steps, written from a spec)
.claude/commands/   slash commands  ->  /<filename-without-.md>
.claude/skills/     skills          ->  .claude/skills/<name>/SKILL.md
.claude/agents/     subagent definitions
.claude/settings.json        shared settings
.claude/settings.local.json  personal settings, not committed
```

Two of these have a default location elsewhere that this file deliberately
overrides:

- **Memory.** Write it to `.claude/memory/`, **not** to the harness default
  under `~/.claude/projects/…/memory/`. Because that default is where memories
  are auto-recalled from, moving them here means **they are no longer injected
  automatically — read `.claude/memory/MEMORY.md` yourself at the start of a
  session**, and follow its links. Keep that file as the one-line-per-memory
  index.
- **Plans.** Plan mode writes to `~/.claude/plans/` by default — move the file
  into `.claude/plans/` once the plan is settled.

## Commands

#### Run the API (defaults to :8080)
```bash
cd planner-api && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Run the API on another port
```bash
cd planner-api && PORT=8080 PUBLIC_BASE_URL=http://localhost:8080/p ./gradlew bootRun
```
#### Run all tests
```bash
cd planner-api && ./gradlew test
```
#### Run one test class
```bash
cd planner-api && ./gradlew test --tests 'ChecklistSeederTest'
```
#### Run one test method
```bash
cd planner-api && ./gradlew test --tests 'NightsTest.countsNightsBetweenTwoDates'
```
#### Compile only
```bash
cd planner-api && ./gradlew compileJava
```
#### Start the API from scratch
```bash
cd planner-api && rm -rf data && ./gradlew bootRun
```
#### Build the stylesheets (required before first serve)
```bash
cd planner-web && npm run css
```
#### Rebuild stylesheets on change
```bash
cd planner-web && npm run css:watch
```
#### Serve the frontend (:3000)
```bash
cd planner-web && ./serve.sh
```
#### Start local Postgres (for database mode)
```bash
docker compose up -d postgres
```
#### Run the API in database mode
```bash
cd planner-api && FEATURE_ENABLE_DATABASE=true BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun
```
#### Serve the frontend through the Cloudflare proxy, as deployed (:8788)
```bash
cd planner-web && npm run pages:dev
```
#### Build the API image
```bash
docker build -t planner-api planner-api
```

## API Port and localStorage

**Always use port 8080 for local development** — this is the default and what the frontend expects.

The frontend (`planner-web/js/config.js`) stores the API base URL in browser localStorage. This means if you ever open the frontend with `?api=http://localhost:NONSTANDARD_PORT`, that port will be **remembered in localStorage indefinitely**. Future page loads will try to reach the API on that port even if you didn't intend it.

**If you use a non-standard port** (e.g., `PORT=8081`), always ensure you:

#### Clear browser storage before switching back
Open your browser's DevTools (F12) and run:
```javascript
localStorage.removeItem('plannerApiBase');
```

Or clear all storage:
```javascript
localStorage.clear();
location.reload();
```

**For agents:** If you must start the API on a non-standard port, include a step that clears the stored API base afterward to prevent confusion.

There is no linter and no frontend test suite. Failures land in
`planner-api/build/reports/tests/test/index.html`, and the machine-readable
detail is in `build/test-results/test/*.xml`.

**Check that container tests ran, not just that the suite is green.** The
Postgres and MinIO tests are `@Testcontainers(disabledWithoutDocker = true)`: if
Testcontainers can't reach Docker they are **skipped**, and a run that tested
no database reports BUILD SUCCESSFUL. On this machine Docker is Colima, which
needs `docker.host` in `~/.testcontainers.properties` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` in `~/.zshrc` (both set — see
`docs/deploy.md`, *Local development*). Count the `skipped` attribute in
`build/test-results/test/*.xml`; it should be 0.

`PUBLIC_BASE_URL` must track `PORT` — it is what the app shows as a published
trip's public link. `app.cors.allowed-origins` must list the frontend origin,
because auth is cookie-based.

## The cascade

The single most important thing about this codebase: **the checklist is the
spine**, and one user action ripples through four modules.

```
add a Destination            DestinationService
  └─ seeds 3 ChecklistItems  ChecklistSeeder      "Plan 6N accommodation in Cusco"
       └─ press Plan         PlanTemplates        pre-fills the form
            └─ ItineraryItem ItineraryService
                 └─ a cost   BudgetSync           creates the matching BudgetItem
```

Rules that are easy to break by accident, all with tests:

- **Nights** (`shared/Nights`) returns null unless both dates exist and end is
  after start. That null is the accommodation test: no dates, or a day trip
  like 24-Oct to 24-Oct, means no room to book, so `ChecklistSeeder` seeds two
  items instead of three and `DestinationService.update` adds the third if the
  dates later come to cover a night — once per destination, tracked by
  `lodgingSeeded`, so an item deleted on purpose does not come back.
- **Nights versus days.** Neither is stored; both are counted from the two
  dates, and which one you want depends on the question. **Nights** is the
  domain primitive, load-bearing in two places: zero nights is what "needs no
  accommodation" *means*, and a room is booked in nights — `Plan 6N
  accommodation in Cusco` matches the hotel confirmation, where `7D` would
  not. **Days** is presentation only, counting both ends, and it is what the
  Destinations table shows because that column is read as "how long are we
  there" — Cusco 25-Oct to 31-Oct is `7D`, and a day trip is `1D`.
  `format.js` has both (`nightsBetween`, `daysBetween`) and `daysBetween` is
  deliberately **not** `nightsBetween() + 1`: nights is null for a day trip by
  design, so adding one would print `—` for the stop that is plainly one day.
- **A country is stored as its code and named by one table.** The API keeps
  `countryCode` (and the flag beside it) and never a name: `Destination.countryName`
  is gone, and budget country slices and the published snapshot carry codes.
  `planner-web/js/countries.js` maps code to name, and every screen that shows a
  country — Destinations table, the checklist, itinerary and budget chips, the
  country picker, the budget chart — reads it, so a name cannot differ between
  tabs. The published page cannot import it, so `npm run countries` writes the
  same table to `planner-api/.../publish/countries.json`, which the renderer
  inlines as `window.COUNTRIES` for `page.js`; `npm run check` fails if the two
  drift. Edit `countries.js`, then run `npm run countries`. A member's home
  country follows the same rule: `User.homeCountryCode`, named by the table.
- **Seeded items are ordinary items once created.** Renaming a destination does
  not rewrite their text; `autoSeeded` is metadata only.
- **Checklist items, itinerary entries and budget rows link to *countries*,
  not to cities.** `countryCodes` on all three, validated by `TripCountries`
  against the trip's own destinations, so nothing can be filed under a country
  the trip does not visit. A country is what every consumer of the link wanted
  anyway — a chip, a filter, a group-by, the budget breakdown — and storing a
  city id only to resolve it to a country on every read was precision that
  existed to be thrown away. Two consequences worth knowing:
  - **`ChecklistItem.seededFromDestinationId` is the city, kept as metadata**
    like `autoSeeded`. `PlanTemplates` needs it: the Plan form suggests a
    description naming the place and, for a stay, that place's own check-in and
    check-out dates, none of which `["PE"]` can answer. The Destinations
    table's per-city checklist count reads it too — counting by country would
    give every city in a country the same number.
  - **The budget's country split is per country, not per city.** A row across
    France and Belgium is 50/50 even when the trip has two French stops. The
    old per-destination weighting went with the link, and a country cannot be
    named twice.
- **"Suppress auto-generated checklist" is stored on the destination, not just
  read off the form.** `Destination.suppressChecklist`, honoured in
  `DestinationService.create` *and* in `seedLodgingIfTheDatesNowNeedIt` — that
  second one is the reason it has to be remembered: the accommodation item is
  seeded on a later date edit, so a flag read once at create time would let
  suppressing the checklist and then filling in the dates quietly produce the
  one item the member said they did not want. Clearing it afterwards lets the
  accommodation item appear (the dates justify it) but never back-fills
  transport and activities, which are create-time only — the same reason
  renaming a destination does not rewrite its seeded text.
- **Deleting a destination unlinks its checklist items, it does not delete
  them.** Losing a place must never discard the planning done against it. With
  country links most deletions now unlink *nothing*: dropping Cusco leaves a
  Peru-linked item alone because the trip still goes to Peru via
  Ollantaytambo. Only a country's last destination detaches anything.
- **Adding a plan never completes a checklist item.** Only an explicit
  `PATCH /checklist/{id}/status` does. That is what "Plan another" exists for,
  and force-completing with zero plans is allowed.
- **A plan and the days it covers are different things.** This is the one
  distinction to keep straight in the itinerary module.
  `ItineraryService.spreadPlanOverItsDays` turns a LODGING entry whose
  check-out falls on a later day into one entry per day, at create time, so
  each night is an ordinary item — selectable, editable and deletable on its
  own. What keeps "one plan" separate from "five days" is `planId`: absent on
  the plan's own row (that row *is* the plan), set to that row's id on every
  later day. So:
  - counting plans against a checklist item means counting rows with no
    `planId` — `plansFor` / `planCountFor` in `js/pages/trip/checklist.js`.
    Count every linked row instead and a 4N booking reads as five plans.
  - the nights shown on a plan card (`planNights`) are counted from the rows
    that exist now, never stored, so deleting a night reads as 3N with nothing
    rewritten.
  - only the plan's row keeps the cost and the budget back-link, so one
    booking is one charge; the days between carry `allDay` and no time.
  - `DELETE /itinerary/{itemId}` removes one day; `DELETE
    /itinerary/{itemId}/plan` removes the plan with all of its days. The
    checklist drawer uses the second, the Itinerary tab the first.
  - deleting a plan's own row promotes the earliest survivor
    (`adoptOrphanedDays`), so a group is never left pointing at a record that
    is gone — present on the itinerary, invisible to anything counting plans.
  - **it is deliberately not named `stayId`.** Only lodging spans days today,
    but the grouping says nothing about category, and a multi-day train filed
    under "stay" would be a lie in the schema.
  - neither renderer expands anything: doing both would show every night
    twice. Create-time only — editing a day edits that day, and moving one
    entry's dates does not re-spread the plan.
- **An entry with a date but no time is all-day, not midnight.** An empty time
  field used to be combined into `T00:00`, which then rendered as a confident
  "00:00" — indistinguishable from a stated midnight departure. The forms now
  send `allDay` alongside the timestamp, because once a date and a blank time
  have been combined the two cases *cannot* be told apart server-side. It
  reuses the same flag a stay's middle nights carry, so both render as "—" with
  no second rule. On a patch, `null` leaves it alone and `false` clears it, so
  adding a time later starts showing it. **The edit forms must prefill a blank
  time for an all-day entry** (`openEditEntry`, `editPlan`): the stored `T00:00`
  is a placeholder, and prefilling it made a plain Edit → Save send
  `allDay: false` and turn the entry into a timed midnight one.
- **`ItineraryItem.coversWholeDay()` is deliberately not named `isAllDay()`.**
  Jackson treats every `get`/`is` accessor resolving to the same property name
  as one property, and a `@JsonIgnore` on any of them drops the whole thing —
  `allDay` then never reaches the YAML and a stay's nights come back timed.
  See Traps.
- **`BudgetSync` is one-way and narrow.** A later cost change updates only
  amount and currency — plus `status`, and only when the form actually sends it
  — so a description someone has since corrected in the budget is never
  clobbered. Clearing the cost, or deleting the plan, removes the row it
  created. A manual expense has no `itineraryItemId` and is never touched.
- **An expense is charged or pending, and the two defaults point opposite
  ways.** `BudgetStatus` on `BudgetItem`, with `confirmedAt` beside it. An
  expense typed into the budget by hand starts **charged** — the form's box is
  ticked — because money entered after the fact has usually already left. A
  plan's cost starts **pending**: a plan is something you intend to do. Both
  forms carry the same "Expense already charged" label and both write through
  `BudgetItem.markCharged`, the one place the transition lives, so the two
  doors cannot disagree about what the states mean. Three rules are easy to
  break:
  - **`CONFIRMED` is the field's default, so a record with no `status` in its
    YAML reads as a charge.** Every row written before this existed was money
    already spent, and a budget that reclassified a whole trip as speculative
    the day the field shipped would be worse than one that never knew the
    difference. `BudgetStatusTest` asserts this against a hand-written file,
    not an object.
  - **`confirmedAt` is stamped once and never moved.** Correcting the
    description of a charge made in March must leave March alone, so
    `markCharged` only stamps when there is nothing there. Un-ticking clears it
    outright — a pending row carrying a confirmation date would be a row
    claiming to have been paid.
  - **A pending row is excluded, never hidden.** It is listed in the table and
    labelled Pending; it is only left out of the figures that claim to be money
    spent.
- **An expense belongs to the members who share it, and the Budget tab is one
  member's money rather than the trip's.** `BudgetItem.sharedByUserIds` holds
  trip-member user ids — the forms label it "Shared by" — and `TripMembers`
  (`trips/api`, a record beside `TripWindow`) owns every rule about them:
  validation, who actually pays, and the division. Four things to keep straight:
  - **Empty means the whole trip**, and that is a safety property rather than a
    convenience. The tab lists a member only the rows they share, so a row
    shared by nobody would be money present in the file and absent from every
    screen. The same fallback catches a row whose named sharers have all since
    left the trip.
  - **The split is resolved per request, never stored.** It depends on how many
    people share the row, so a stored copy would go quietly wrong the moment
    somebody was added — the same reason nights are not stored. A member who
    leaves drops out of the split without their name being rewritten, so
    re-adding them restores it.
  - **`Summary.items` stays whole; `Summary.shares` is the filter.** Two
    filters would otherwise fight over one field: the other tabs read every
    budget row (the Plan form wants the status and sharers of the row its own
    cost created, whoever ended up sharing it), so what narrows the Budget tab
    is a lookup in `shares` — itemId to that member's part — not a shorter
    `items`. A row absent from the map is somebody else's; an entry of zero is
    theirs and costs nothing, which is why the frontend tests for null rather
    than for truthiness.
  - **Dividing happens before converting and slicing.** `BudgetService.Charge`
    pairs a row with the amount of it that counts, and every sum reads the
    amount from there rather than from the item — otherwise a half-share would
    be converted, split by country and then quietly totalled at full price. A
    published page has no signed-in reader, so it passes every row in full and
    ships an empty `shares`.
- **"Paid by" is who put the money down, and it is never counted.**
  `BudgetItem.paidByUserId` is one trip member or nobody. Paying for the
  table's dinner does not make it your dinner: whose money a row is stays
  decided by `sharedByUserIds` alone, and `thePayerChangesNoFigure` pins that.
  Five things:
  - **A charged expense must name one.** `BudgetService.requirePayerWhenCharged`
    refuses it, because money that has left someone's hand with no record of
    whose can appear in no settlement — it would go missing from Settle
    Expenses silently, in the one direction a reader cannot detect. A
    *pending* row stays optional: nobody has paid it, so a payer would be a
    guess. **The check asks the item, not the input**, which is what makes one
    call cover all four doors — creating a charged row, ticking "already
    charged" on a payer-less one, clearing the payer of a charged one, and a
    plan's charged cost through `BudgetSync`. Checking the request instead
    would need four checks and still miss the fifth door somebody adds.
  - **Enforced on write, never on read.** These files are hand-editable and
    installs exist that predate the field, so a charged row with no stored
    payer still loads; `BudgetService.effectivePayerOf` reads it as its
    `createdByUserId` for settling. It is a static method rather than a getter
    on `BudgetItem` — a derived getter serialises into the YAML and then fails
    to read back (see Traps) — and nothing is written back.
  - **Three answers, not two.** On a PATCH (`paidByUserId` on budget,
    `costPaidByUserId` on itinerary) absent/`null` leaves it alone, `""`
    clears it, an id sets it. The forms therefore always send the field, as
    `|| ''`, or clearing it in a form would silently do nothing.
    `TripMembers.validateOne` collapses blank to null, so
    `ItineraryService.validatedPayer` keeps the blank before `BudgetSync` sees
    it.
  - **A new cost defaults to the member filling in the form.** Clicking your
    own chip clears it *while the cost is pending*; `member-picker.js`
    `choosePayer` refuses to clear once "already charged" is ticked, and
    `chargedToggled` selects you when the box is ticked with nobody named — so
    the state the API rejects is unreachable from the forms rather than a
    failure on Save. Both live in `member-picker.js` because three forms spell
    the field two ways (`charged`, `costCharged`). Editing a plan that already
    has a budget row shows that row's payer (`paidByOfPlan`).
  - **Planner only.** `StaticSiteRenderer` never names it, so no payer reaches a
    published file. A payer who has since left the trip reads "Former
    member": only current members' names are known to the page.
- **Settle Expenses answers "what do I owe you", and only charged rows count.**
  `BudgetService.Settlement` — one per other member per currency, carrying
  `owesYou`, `youOwe`, `net` and the `lines` behind them — reached through
  `Summary.settlements`. Five rules:
  - **A debt needs money to have moved.** Pending rows settle nothing, and this
    is deliberately *not* wired to the panel's Group by selector: under
    Forecast it would invent debts for expenses that have not happened.
  - **The division is `TripMembers.shareOf`**, the same call the breakdowns
    use, so the figures reconcile with the Budget tab to the cent. Dividing
    again in the browser — where it would be `amount / n` — drifts on any row
    that does not divide evenly, and the drift surfaces as two members
    disagreeing by a penny about the same dinner.
  - **Per currency, never converted.** A debt is repaid in the currency it was
    run up in, so a converted settle figure would be a number nobody can hand
    over, and it would move with the rates besides.
  - **Nobody owes themselves**, and a payer who has left the trip is not
    settled with — the trip no longer knows them, the same reason their share
    drops out of a split.
  - **It never reaches a published page.** `settlements` is empty with no
    signed-in member, and the two published records (`PublishedTrip.Budget`,
    built by hand) never name it. The member-only route is
    `TripViews.BudgetView`, which is a *different record* — adding a field to
    `Summary` alone does not reach the frontend, which is exactly the "Views
    versus domain" separation working. Pinned by
    `noSettlementReachesAPublishedFile`, which greps the rendered file
    including a personal page — the one published file rendered *for* a
    viewer, and so the only one where settlements are computed at all.
  - **`app.settlement.simplify-debts` (`SIMPLIFY_DEBTS`, default off) nets debts
    across the whole trip.** `SettlementProperties`, a record of its own, read
    by the `@Autowired` constructor of `BudgetService`; the 7-argument one
    stays and means off, so tests and `ImportVerifier` are untouched. On,
    `BudgetService.simplify` computes every member's balance per currency from
    the charged rows, then matches the largest creditor with the largest
    debtor until nobody is owed — at most n−1 payments per currency. Four
    things to keep straight:
    - **The plan is the trip's, not the viewer's.** Every member computes it
      independently, so nothing in the matching may depend on who is asking;
      ties break on user id. The viewer only filters the finished plan to the
      payments they are part of. `anyTangleNeedsAtMostOneFewerPaymentsThanPeople`
      checks it from every chair.
    - **A simplified payment has no rows behind it**, so its `Settlement.lines`
      is empty. The details view shows `Summary.balances` instead — the
      member's own standing per currency, with the rows that add up to it (rows
      that net to nothing for them are left out, but a currency that nets to
      zero is still listed, so "square" can be seen) — and
      `settlementsSimplified` tells the page which to show. That is one View
      details button in the panel's header rather than one per row, since a
      balance belongs to the member and not to a pair.
    - **Everything above still holds**: charged rows only, per currency and
      never converted, `TripMembers.shareOf` for every division (which is why a
      currency's balances sum to exactly zero), a departed payer skipped, and
      neither `settlements` nor `balances` on a published page.
    - **Someone may be told to pay a person they never shared an expense with.**
      That is the point of simplifying; the settle note says so.
  - **A payment settles a debt, and is its own record — not an expense.**
    `SettlementPayment` (`budget/domain`), stored per trip like the other five
    (`travels/settlements/<slug>.yml`, table `settlement_payments`, Flyway
    `V10`). It is deliberately **not** a `kind` on `BudgetItem`: every reader of
    expenses — the table, both breakdowns, native totals, forecast, the other
    tabs, the publisher, the importer's verifier — would have to remember to
    skip it, and the fifth door somebody forgets would inflate the trip's cost
    by every repayment. As its own record it is excluded from all of them by
    construction; only the settlement maths in `BudgetService` reads it, and
    `aPaymentChangesNoFigureAnywhere` pins that. Five things to keep straight:
    - **To the ledger it is "paid by `from`, shared by `to`"**: the payer is
      credited, the receiver debited, per currency, never converted. Simplified,
      it joins the whole-trip ledger before the matching, so the remaining plan
      shrinks and its rows appear in the balance breakdown (`Balance.Line.paymentId`).
      Pairwise, `Settlement` gains `paidYou` / `youPaid` and
      `net = owesYou − youOwe − paidYou + youPaid`; `owesYou` / `youOwe` stay
      gross so a paid-off pair still says what happened. A pair that nets to
      zero stays listed as Settled, so its payment can be seen and undone.
    - **Either party or the owner records or deletes one**
      (`SettlementService.requireInvolved`); any other member gets 403, a
      non-member 404. The author is `createdByUserId`; there is **no edit** —
      delete and record again — so `updatedBy…` stays absent — and no
      confirmation from the receiver.
    - **A payment naming somebody who has left is ignored** by the maths
      (`countedPayments`), like a departed payer's expense, and kept in the
      file so re-adding them restores it.
    - **It never reaches a published page.** `Summary.payments` is empty with
      no signed-in member, `PublishedTrip` never names one, and
      `noPaymentReachesAPublishedFile` searches every rendered file for the
      amount and the note.
    - **Deleting a trip must delete them**: a sixth file in
      `YamlTripRepository.delete`, `ON DELETE CASCADE` in Postgres, and the
      importer copies, counts and verifies them (`settlement payments`).
      `BudgetService` reaches the repository through its nine-argument
      `@Autowired` constructor; the shorter ones leave it null, meaning none.
    - **Frontend:** each row of the settle table has **Record payment**
      (prefilled from the row; the amount can be lowered for a part payment),
      and a **Payments** list under the table has Delete. The currency is
      fixed by the row — a debt is repaid in the currency it was run up in — so
      it is part of the label ("Amount (PEN)"), not a field that looks editable.
      The dialog says what saving would leave *before* it is saved (`payOutcome`:
      still owed, settled, or an over-payment that turns the debt round), worked
      in whole cents, and the toast repeats the outcome ("You paid Sam 24.91 PEN
      — 600.00 PEN left"). Toasts have no action button, so there is no Undo on
      it; Delete beside the payment is the undo.
      The settle table is a `table-cards-plain` card on a phone — with two
      buttons per row, the default card layout squeezed the details into a
      sliver.
- **The budget rollup is computed twice, and the two halves never mix.**
  `BudgetService.Summary` carries `charged` and `forecast`, each a whole
  `Breakdown` — category slices, country slices, native totals, total and
  missing rates over one set of the signed-in member's shares. Group by chooses between them
  (`budgetView` in `js/pages/trip/budget.js` resolves it in one place) and
  every number on the panel follows together, which is the point: a total from
  one set of rows shown above a breakdown of another is the single way this
  panel can lie. The headline says which it is — `Total:` or `Forecast
  total:` — rather than leaving a reader to infer it from the selector.
- **The trip's dates bound what is *shown*, not what can be entered.** A
  destination, plan or itinerary entry may be dated outside the trip: the API
  no longer rejects it (`TripWindow` is no longer used by any service). Instead the
  form saves and then raises a sticky warning toast (`warnIfBeyondTrip` in
  `js/pages/trip.js`) saying the date is beyond the trip and will not appear in
  the itinerary. `itineraryDays` in `js/pages/trip/itinerary.js` and
  `StaticSiteRenderer.days` leave out any day outside the trip's own dates; the
  rows stay stored. Two forms differ: an expense date is unrestricted and gives no warning, and the
  Itinerary tab's own entry form still holds dates to the trip (`min`/`max` plus
  `dateOutsideTrip`, client-side only).
- **Who's going is inherited; money is copied once.** Destinations,
  checklist items and plans carry an optional `travellerIds`, resolved in one
  place, `trips/api/Travellers`: a destination names buddies or is the whole
  trip; a checklist item follows the destination it was seeded from; a plan
  follows its checklist item; a stay's later days always follow their plan
  (the API refuses a list of their own). Three states, and the first two
  differ: null is "not set, follow the parent" — so a later edit to Cusco
  carries down to everything nobody edited — and `[]` is explicitly the whole
  trip. That is why `traveller_ids` is a **nullable** `text[]` with no default,
  unlike `shared_by_user_ids`, why `JdbcValues.nullableTextArray` exists, and
  why `BaselineSchemaTest` pins the column. An empty *resolved* list means the
  whole trip, as with Shared by. Four more things:
  - **The page is sent `mine` and `travellers` and never re-derives them**,
    the same pattern as `budget.shares`, so the planner and a personal
    published page cannot disagree. Link pickers keep reading the whole lists.
  - **A new cost starts shared by the plan's travellers only when the plan is
    for particular buddies**; otherwise it keeps the old default, the member
    entering it. The forms send what they show (`defaultCostSharers` in
    `traveller-picker.js`); `ItineraryService.defaultSharers` covers a request
    that sends none. After that the row never follows: moving money between
    buddies silently is the one thing this must not do.
  - **Only the owner has the Mine / Whole trip switch**; every other buddy is
    always on Mine (`canSeeWholeTrip` in `js/pages/trip/scope.js`), with no
    "N more on the whole trip" hint, since they could not act on it. **Focus
    view, not privacy**: every member is still sent every row. Only a personal
    published page leaves other buddies' parts out of the file.
  - **An untouched picker sends nothing** (`travellersPayload`), so opening and
    saving a form never turns "not set" into `[]`.

## Backend architecture

Feature modules, each with the same four layers — `domain/` (stored records),
`api/` (services, where the rules live), `infra/` (repository interface plus its
YAML and JDBC implementations), `web/` (controllers and request/response types).

**Storage: two stores behind one set of interfaces.** Every repository is an
interface — `UserRepository`, `TripRepository`, and the six that were concrete
YAML classes until the database work (`Budget`, `Checklist`, `Destination`,
`Itinerary`, `Weather`, `Rates`), which kept their names so **no service
changed**. Each has a `Yaml…` implementation, `@ConditionalOnProperty(name =
"feature-enable-database", havingValue = "false", matchIfMissing = true)`, and a
`Jdbc…` one with `havingValue = "true"`. The flag (`FEATURE_ENABLE_DATABASE`)
defaults to off: local development is YAML, the cloud deployment is Postgres.
The plan and its reasoning are in
`.claude/plans/2026-09-18-postgres-and-cloud-run.md`.

- **The per-trip entities share one base per store.** `TripScopedRepository<T>`
  is the interface; `TripScopedYamlRepository` (one YAML list per trip,
  replaced wholesale under that trip's lock) and
  `storage/jdbc/TripScopedJdbcRepository` implement it. Adding another per-trip
  entity means extending both bases, not copying either. The module finders
  (`findAllOrdered`, `findByItineraryItem`, `findByChecklistItem`) are
  **`default` methods on the interfaces**, so the sort rules exist once and the
  two stores cannot disagree about them.
- **With the flag off, YAML mode starts exactly as it did before the database
  existed.** The JDBC starter and Flyway are on the classpath, so Spring Boot
  would build a DataSource on every start and die with "url not specified".
  `config/DatabaseModeEnvironment` (an `EnvironmentPostProcessor`, registered in
  `META-INF/spring.factories`) prevents that: with the flag off it adds the
  DataSource, JDBC, SQL-init and Flyway auto-configurations to
  `spring.autoconfigure.exclude`, merged with any excludes already set.
  `YamlModeStartupTest` pins it, and was written before the dependency was added
  precisely so this couldn't break unnoticed.
- **Flyway owns the schema, and only the schema.** `db/migration/` never
  inserts, updates or deletes rows — `MigrationsAreSchemaOnlyTest` enforces it.
  Data moves once, through the importer (application code, not a migration).
- **The schema rules** (`V1__baseline_schema.sql`), each there because a
  cheaper choice would silently change behaviour:
  - every per-trip table is keyed `(trip_id, id)` — weather ids repeat across
    trips — and carries `seq`, an identity column recording insertion order.
    Budget and weather have no `sortOrder` and tie-break on YAML list order;
    `seq` is that order. Upserts leave it alone; `replaceAll` re-inserts in the
    order given.
  - **order that decides money is stored as order.** `trip_members.position`
    and `budget_items.shared_by_user_ids` as an ordered `text[]` — the odd cent
    of a split goes to the earliest sharer, so a set would move pennies between
    people.
  - itinerary `start_at`/`end_at` are `timestamp` **without** time zone:
    wall-clock time at the destination. `timestamptz` would shift a 06:00
    departure by the JVM's zone.
  - foreign keys only where the app guarantees the target exists — `trip_id →
    trips ON DELETE CASCADE`, owner and members → `users`. Cross-row links
    (itinerary↔budget, checklist↔itinerary, `seededFromDestinationId`, payer,
    sharers, audit ids) have none, because the app deliberately keeps them after
    their target is gone ("Former member", unlinking, `adoptOrphanedDays`).
  - enums as `text` with no `CHECK`, so adding a category stays a four-place
    change rather than five.
  - `Trip.exchangeRates` has no column: it is "no longer read", kept only so old
    YAML loads.
- **Repositories resolve the trip from the slug, never from the entity.** The
  per-trip methods are keyed by slug (immutable, set once in `TripService`);
  SQL uses `trip_id = (SELECT id FROM trips WHERE slug = :slug)` and ignores
  whatever `tripId` the entity carries.
- **Every column has an every-field round-trip test that fails when a domain
  field is added but not mapped** — `storage/EveryField` (test code). Add a
  field to a stored class and the round-trip tests for it fail until the
  fixture sets it, which is the moment to add the column and map it.
- **YAML and Postgres are proven equivalent, not assumed.** Each repository has
  one abstract `…RepositoryContract` run by two subclasses — YAML (temp dir) and
  Postgres (`@PostgresTest`, Testcontainers). A behaviour that differs between
  the stores fails a test. The few intentional differences (microsecond
  timestamps, `exchangeRates` unstored, database-enforced uniqueness) are
  pinned by name.
- `DatabaseModeApplicationTest` boots the **whole application** on Postgres and
  runs a trip through the real services — the cascade, the budget, the view
  assembly and a delete that must leave every table empty.
- **A trip's file is the single source of truth.** `trips/index.yml` is only an
  id-to-slug directory, so there is no duplicated trip data to fall out of sync.
- **Deleting a trip must delete everything it put anywhere.** The five per-trip
  YAML files go in `YamlTripRepository.delete`; in database mode one `DELETE
  FROM trips` does it through `ON DELETE CASCADE`, and
  `JdbcTripRepositoryContractTest.everyTableReferencingTripsIsCovered` reads
  every foreign key onto `trips` from the catalogue and fails if a new table
  isn't in its cascade test (test-only tables must be named `test_…` — see
  `NoteRepository`). The *rendered public page* goes
  in `TripService.delete`, via `renderer.remove(slug)`. That second one is the
  easy cascade to forget and the only one where forgetting is a privacy
  problem rather than a tidiness one: a published page is a plain static
  directory, so with no trip behind it nothing would ever remove it and it
  would keep serving the whole plan at its public URL. Three orphaned
  directories were found doing exactly that. Pinned by
  `AuditTrailTest.deletingATripRemovesItsPublishedPage`.
- `TripLocks` guards read-modify-write; `YamlStore` writes to a `.tmp` and moves
  it into place. Both are process-local, which is why YAML mode is
  **single-instance only**. The JDBC repositories don't use `TripLocks`: two
  row upserts can't clobber each other the way two rewrites of one YAML file
  can. Service-level read-modify-write sequences are exactly as racy in both
  modes, and service-level `@Transactional` was deliberately left for later —
  hence `--max-instances=1` on Cloud Run.
- **Derived values are never stored.** Nights are recomputed from the dates, in
  Java for the seeder and in JS for the destinations table.

**Permissions** live entirely in `TripAccessService` — `requireMember` or
`requireOwner` at the top of each service method. Non-members get 404, not 403,
so trip ids cannot be probed. Owner-only means exactly three things: deleting the
trip, publishing it, and removing somebody else (anyone may leave).

**Views versus domain.** `TripViews` / `TripViewAssembler` produce the API shape;
domain objects are the storage shape. Keep them apart — a derived getter added to
a domain object gets serialised into the YAML and then rejected on read (see
Traps).

**Who did this.** Every member-editable record implements `shared/Audited` and
carries `createdByUserId` / `updatedByUserId` next to its timestamps —
destinations, checklist items, itinerary entries, budget rows and the trip.
`shared/Audit` has the two calls, `created` and `touched`, and nothing else
should write those fields. Four rules, all with tests in `AuditTrailTest`:

- **A user id, never a name or an email.** Both of those change, and the member
  list already resolves a screen name per request, so a stored copy would be a
  stale answer to a question the user record answers correctly. An id also
  survives a member being removed, which is exactly when the question gets
  asked. `PublishRequest.requestedByUserId` and `TripMember.invitedByUserId`
  are where the `…ByUserId` naming comes from.
- **The services stamp, not the repositories.** The actor is a property of the
  request, which a repository deliberately knows nothing about. More
  importantly a write is not an edit: `replaceAll` rewrites a whole file to
  unlink three rows out of fifteen, and a repository hook would record twelve
  edits nobody made. Only the caller knows what it changed — which is why the
  unlink loops, `reorder` and `adoptOrphanedDays` stamp inside their
  conditionals, and `reorder` skips a destination whose position did not move.
- **`updatedAt` / `updatedByUserId` stay absent until a real edit.** A record
  showing a creator and no editor has not been touched since, which is a real
  answer worth reading off the file, and it keeps the YAML the size it was.
  (Trip is the exception: `YamlTripRepository` has always stamped both of its
  timestamps on every save, and that was left alone.)
- **Records the app writes for you are attributed to the member whose action
  produced them**, or the only rows with no author would be exactly the
  automatic ones. The seeded checklist items belong to whoever added the
  destination; a lodging item seeded later by `DestinationService.update`
  belongs to whoever's *edit* made the dates cover a night, not to whoever
  created the dateless destination; the nights a stay spreads over and the
  budget row a plan's cost creates belong to the plan's author — which is why
  `BudgetSync.afterSave` takes a `userId`.

**Where they show up.** `TripViews` passes the four trip-scoped domain objects
through unwrapped, so these fields *do* reach `GET /trips/{id}` — which is fine
and useful: it is member-only, and `members[].userId` already hands out the same
ids, so nothing new is disclosed. A published page is the opposite case and safe
for the opposite reason: `StaticSiteRenderer` builds the flat `PublishedTrip`
record by hand and never names an audit field, so no user id has ever reached a
public HTML file. Keep it that way — that separation is the whole point of
"Views versus domain" above. Nothing in the frontend reads these yet; the YAML
is the debugging surface.

**Auth.** JWT in an httpOnly cookie plus a double-submit CSRF cookie echoed as
`X-XSRF-TOKEN`. The user is re-read from storage per request, so a screen-name
change takes effect immediately. Filter order is set explicitly in
`SecurityConfig`: `CsrfFilter` → `JwtCookieAuthFilter` → `PasswordChangeGate`.
While `mustChangePassword` is set the gate answers **409
`password_change_required`** to everything except `/auth/me`, `/auth/logout` and
`/account/password`; `js/api.js` turns that into a redirect.

**On screen a member is a "travel buddy".** The Travel Buddies tab, the
"Travel buddy" role badge, the counts, the emails and the API's error messages
all say it. The code, the API paths (`/members`), the `#members` tab link and
these docs keep "member": renaming a URL would break bookmarks and a code
identifier gains nothing. Keep new user-facing text on the screen's word.

**Accounts only exist via invitation.** `UserService.findOrCreate` is called when
a member is added, so every invited member has an account immediately. That is
what makes the display rule work without a separate "pending invite" concept:
`screenName != null ? screenName : email`.

**Publishing** renders one self-contained HTML file per trip — stylesheet, script
and data all inlined, no external requests except the reader's own weather
lookup — from `resources/publish/page.css` and `page.js`. Those assets live in
the API on purpose, so publishing never depends on the frontend having been
built. Output is a plain static directory; copying it to a CDN is the whole
deployment. Its one absolute URL is the "Planned with 🦙 Travelling Llama"
link at the foot of the page. It is an anchor, not a fetch, so it doesn't break
self-containment, and it points at `AppProperties.Cors.siteUrl()` (the first
CORS origin, the same site the emails link to), never at the API. The
self-containment test cuts out that exact link and still fails on any other.

**Where pages are written is a `PageStore`** (`publish/infra`), chosen by
`app.publish.store` and **independent of the database flag**:
`FileSystemPageStore` (the default — today's directories, used locally) or
`R2PageStore` (Cloudflare R2 over its S3-compatible API, used in the cloud). The
publish code only ever touches pages through it — `StaticSiteRenderer`,
`PublicPageController` and `TripViewAssembler` — so everything below holds for
both. Four things are easy to break:

- **Approving a request renames a whole directory** on the filesystem. That is
  why the cloud stores pages in R2 rather than on a mounted Cloud Storage
  bucket: Cloud Storage FUSE refuses directory renames on an ordinary bucket.
  `R2PageStore.promote` is copy-then-delete, deleting the live page first so a
  failure leaves no page rather than a half-merged one — the filesystem's own
  ordering.
- **Listing a trip's personal pages is one call.** `TripViewAssembler` used to
  check for a file once per member on every trip load; on R2 that would be one
  network round trip per member. `PageStore.publishedMemberPages(slug)` lists
  them in one.
- **One private bucket, two prefixes**: `published/<slug>/…` and
  `pending/<slug>/…`. Nothing about the bucket is public. The Cloudflare
  Function `planner-web/functions/p/[[path]].js` serves `/p/*` straight from R2
  — so a public reader never wakes the API — and it only ever reads
  `published/`, validating each segment the way `Slugs.requireSafe` does. It
  mirrors `PublicPageController`'s routes exactly; when one changes, change the
  other.
- The R2 client is created **lazily**, on first publish, so the AWS SDK adds
  nothing to a cold start that doesn't publish. Settings are the `app.r2.*`
  record `R2Properties`, bound only when the R2 store is selected.

**Owner approval is a switch.** `app.publish.require-owner-approval`
(`REQUIRE_OWNER_APPROVAL`, default `true`), bound by its own record
`PublishApprovalProperties` — not `AppProperties`. On, everything below holds.
Off, `PublishService.publish` accepts any member (the page uses *that*
member's theme and settings, as the owner's direct publish does), so
re-publishing is open to all, and `requestPublish` refuses with a 400 because
there is nothing to ask. Unpublishing stays owner-only in both modes. The flag
reaches the page as `PublishView.requireOwnerApproval`, and `publish.js`
derives `canPublish` from it, so the request card, the Recent requests panel
and the Publish/Re-publish buttons follow the server rather than assuming.
Requests already pending when it is flipped off can still be approved or
rejected by the owner.

**A publish request builds the page; approving it only reveals it.** A member
who is not the owner cannot publish, and their request now renders the page
immediately — in **their** theme and **their** published-page settings — into
`data/published-pending/<slug>/`. Approving *moves* that file into
`data/published/<slug>/` untouched, so what goes public is what they asked to
publish rather than a rebuild using whatever the owner has configured. The
`theme` argument to `approveRequest` is deliberately ignored, and
`PublishRequest.theme` records the requester's, which is the only surviving
record of what the live page looks like.

- **Staging is a sibling directory, not a subdirectory of the publish dir.**
  That directory means exactly one thing — everything in it is public, which is
  what lets it be copied to a CDN unchanged. A staged page living there, however
  well hidden by the UI, would be a leak waiting for somebody to guess a slug,
  and slugs come from trip titles. `PublicPageController` is untouched: it still
  just serves files, because nothing unapproved is ever among them.
- **No URL exists before approval** for free: `TripViewAssembler.publicUrl`
  already answers null for a trip that is not `PUBLISHED`.
- **The page is a snapshot.** Changes made between request and approval are not
  picked up — approving publishes what was asked for. `goLive` falls back to
  rendering (still with the *requester's* settings) when nothing is staged, so
  a request made before staging existed cannot leave an owner with an approval
  that did nothing.
- **Three paths throw the staged page away**, and forgetting any of them would
  leave a page to go live later or outlive its trip: reject, withdraw, and the
  owner publishing directly — plus `TripService.delete`, which removes both
  directories.
- `/api/v1/trips/{id}/publish/preview` serves the staged page to **members
  only**, `no-store`. That is what building it early buys: the owner sees
  exactly what would go public before deciding.

**What a published page shows is an account setting, and every one of them
defaults to off.** `Account → Appearance → Published page`, stored on the
`User` (so `users.yml`, not `localStorage` like the theme beside it) and read
from the member whose page is being written at publish time — so ticking a box
applies to the next publish, not retroactively, because a published page is a
rendered file. `AuthDtos.PublishedPage` groups them and `PATCH
/account/published-page` takes only the flags being changed, so the next
checkbox is one field rather than a new endpoint. There are three:
`itineraryCost`, `destinationDays` and `forecastExpenses` — the last gating
whether a published page offers its two Forecast options under Group by at
all. **The planner's own Budget tab always offers all four**; the setting is
about what a public page reveals, and what a trip is still going to cost is a
more private number than what it has cost so far. `PublishOptions` carries
them into `StaticSiteRenderer`, so `render` does not grow a boolean parameter
per feature — `render(trip, false, true)` says nothing about which is which.

**They are one stored document, not a field each.**
`PublishedPageSettings` on the `User` — `published_page jsonb` in Postgres, a
nested `publishedPage:` mapping in `users.yml`, the same shape either way — so
the next setting is a key inside it rather than a column, a migration, an
upsert list and a row mapper. The wire format stays flat (`{itineraryCost:
true}`), because the page posts one checkbox at a time and nesting the request
would only make it deeper without making it say more. `JdbcValues.json` binds
it as text and the statement casts it (`:publishedPage::jsonb`), so nothing
depends on PgJDBC being on the compile classpath.

The rule that matters: **"not displayed" has to mean "not shipped".** A
published page is public, so a value left in `window.TRIP` is readable by
anyone who opens the source even when nothing renders it — hiding with CSS or
JS would be theatre. `StaticSiteRenderer` therefore leaves a gated value out of
the snapshot, and the test asserts the number appears nowhere in the file
rather than just nowhere in the markup. The payload's mapper is not
`NON_NULL`, so what a reader sees is the key with a `null` — the figure itself
is absent, which is what matters; `page.js` gates on the falsy value
(`budget.forecast`, `destination.nights`) exactly as it would on a missing
key. Each flag is a
primitive `boolean` for the same kind of reason: `YamlStore` serialises
NON_NULL, so a `Boolean` would be absent from `users.yml` until first set, and
a setting you cannot see in the file is one nobody knows is there.

**Publishing a trip publishes it for everybody on it.** Alongside the trip's
own page, every current member gets one of their own — the same trip narrowed
to their destinations, their checklist, their itinerary and their share of
each expense — written to `<published>/<slug>/m/<member>/` and served at
`/p/<slug>/m/<member>`. There is nothing to opt into: publishing a trip is
publishing it *for the people on it*. This is also the only answer a static
page can give to "show me only my own", because there is no sign-in and no API
behind the file, so **whose trip it shows is decided when the file is written,
not when it is read**. Five things hold it together:

- **Another member's rows are not in the file at all.** Filtering in
  `page.js` would be theatre — anyone can open the source and read
  `window.TRIP` — so the renderer takes a `viewer` and narrows the snapshot to
  them: `Travellers.includes` for destinations, checklist and itinerary, and
  `BudgetService.summarise(trip, viewer)` for the money. It is the same
  resolution the planner's own Mine/Whole-trip switch uses, so a member's page
  and their live view of the trip cannot disagree. `PersonalPageTest` searches
  the whole rendered file for the other member's number rather than checking
  the markup.
- **Each page is rendered with its own member's settings**, not the publishing
  member's: it is their page, so what it reveals is their choice — which is
  why one publish can put costs on one member's page and leave them off
  another's.
- **The pages nest inside the trip's directory rather than sitting beside it.**
  A sibling `<slug>-<member>` would be prettier and is a trap: slugs come from
  trip titles, so "LATAM" and "LATAM 2026" give `latam` and `latam-2026`, and
  any cleanup sweeping `latam-*` would delete the second trip's whole page.
  Nesting means unpublishing or deleting the trip takes every personal page
  with it for free.
- **Every publish clears `m/` before writing it.** Publishing again rewrites
  `index.html` in place, so without that somebody who has since left the trip
  would keep serving their own copy of it at a URL nobody is watching any
  more. Pinned by `leavingTheTripAndPublishingAgainTakesThatPageDown`.
- **The budget's `displayCurrency` on a published page is `totalsCurrency`, not
  the trip's anchor.** Those coincided for as long as only the trip was ever
  summarised; a member's totals convert into whatever display currency they
  keep, so the trip page says EUR and a member's page can correctly say SGD.
  Labelling a personal page with the trip's anchor put "EUR" under a column of
  SGD.

The link is offered from the Publish tab and checked **against the file**, not
against the membership: the page is written on publish, and a link offered in
between would be a 404 to share.
`PersonalPages.slugsFor` is the single source of the directory name, used both
by `PublishService` (to write) and by `TripViewAssembler` (to link) — two copies
of "slugify the display name, then de-duplicate" would agree until two members
shared a screen name. **The slug follows the display name**, so renaming
yourself publishes to a new URL and the old link stops working; and it is
guessable from the trip's own URL, which is the trade-off for a readable one.

**Nobody is asked which theme to publish in.** The page uses whatever theme the
publishing member is looking at, read from `savedTheme()` at the moment they
publish — a getter, not stored state, so switching theme and publishing again
is how a page gets restyled. Approving another member's publish request uses the
*approving owner's* theme; they are the one publishing it.

**The published page's Show bar is a multi-select**, though it still looks like
the row of pills it always did: `Show All · Destinations · Checklist · Weather
Forecast · Itinerary · Budget`, and the sections combine — budget + itinerary,
or budget + weather + itinerary. A click toggles one section in or out rather
than replacing the selection, so **several buttons carry
`aria-pressed="true"` at once**, which is correct for toggle buttons and is
what the themes already style. `Show All` is exclusive with the rest (pressing
it clears them, pressing one clears it), and turning off the last section falls
back to it, because a page showing nothing is not a state worth being able to
reach. `normalise()` enforces all of that in one place, which is also what
makes a stale or hand-edited `?show=` harmless.

`?show=` is comma-separated now (`?show=weather,itinerary,budget`) and a
single value still reads correctly, so every link shared before this keeps
working. Absent or `all` means everything.

**It filters parts, not just panels.** Weather and itinerary live in the *same* panel — a
day holds both — so two things generalised to make that work, and both matter
if another panel ever splits:
  - a panel's `data-panel` may name **several** keys (`"weather itinerary"`),
    so `show()` does a membership test rather than an equality check;
  - within it, `[data-part="weather"]` and `[data-part="itinerary"]` are hidden
    independently, and a `.day` left with neither visible is hidden too — or its
    date heading would strand over nothing. That is why "Weather Forecast" shows
    8 days where "Itinerary" shows 9: one day has a plan but no destination
    covering it.

  The panel heading swaps to `🌤 Weather Forecast` when only that half shows,
  from a `data-heading-weather` attribute on the heading itself, so no second
  place lists what each key is called. The reader-side lookup runs once on load
  regardless of the filter, so switching to Weather Forecast never waits.

**The published page lets its reader switch theme.** Every palette
`page.css` can style is inlined in every page as a `:root[data-theme]` block,
and the html element's `data-theme` is the only thing selecting between them —
so the switcher writes one attribute and makes **no request at all**, which is
what keeps it working on a CDN with nothing behind it. Three things to know:
  - the trip's published theme is the *starting point*, not the last word: a
    reader's choice overrides it and is remembered under `publishedTripTheme`
    for every published trip on that origin;
  - the choices travel with the page as `data-themes` on the html element,
    filled from `StaticSiteRenderer.offeredThemes()`. `THEMES` therefore stays
    the single source of what is on offer — withdraw a theme and newly
    published pages stop offering it, with no second list in `page.js` to keep
    in step. A page published earlier keeps the list it shipped with, which is
    inherent to a static file and harmless: its palettes are inlined too;
  - `THEME_ORDER` in the renderer lists every theme `page.css` can style,
    including the withdrawn ones, purely to fix the button order. Re-offering a
    theme is one edit to `THEMES`.
  - `page.js` hides the bar entirely below two themes, so a single-theme
    install and a page published before this existed both render unchanged.

## Frontend architecture

No bundler. Alpine is vendored at `vendor/alpine.min.js`.

`trip.html` is one page with hash-routed tabs and **one** request for the whole
trip (`GET /trips/{id}`); every tab reads from that and calls the shared
`reload()` after mutating. Each tab is a factory in `js/pages/trip/` returning
its own state and methods, merged into one Alpine component by `trip.js`.

**Stylesheets are token-based.** Nothing in `scss/_core.scss` names a colour or a
font — it all reads CSS custom properties, and a theme file defines those under
`body.theme-<name>`. A theme is ~40 lines. Adding one takes four edits: a token
file, an entry point, a `THEME_REGISTRY` entry in `js/theme-selector.js`, and a
`<link id="<name>-css" disabled>` plus the name in the inline pre-paint script in
each page's head — plus the sass command in `package.json`, and `THEMES` in
`StaticSiteRenderer` if published pages should offer it.

**Three themes are on offer — minima, y2k, dark — but five are written.**
Retro-Game and Manila were withdrawn from the registries with their sources
kept on purpose: `scss/retro-game.scss`, `scss/manila.scss`, their
`scss/themes/_*.scss` token files, and their `:root[data-theme]` blocks in the
API's `publish/page.css`. Re-offering either is the edits above and no
restyling. They are deliberately **not** in the `package.json` sass command, so
nothing compiles them and no stale CSS sits in `assets/css/`.

Withdrawing a theme is safe without a migration because both sides already fall
back: `savedTheme()` returns `DEFAULT_THEME` for a name not in the registry, so
a member still holding `retro-game` in `localStorage` silently gets Minima on
their next load, and `StaticSiteRenderer.safeTheme` does the same for a publish
request that names a theme no longer offered. A trip stores no theme of its own:
the page's theme is baked into the rendered file and passed to `render` at every
publish (the publisher's, or the requester's for an approved request), so
nothing reads it back.

**Detail views are popup or side panel, by setting.** `js/panel-mode.js` stores
the choice and puts `panels-popup` or `panels-side` on the body;
`scss/components/_panel-mode.scss` swaps the geometry inside a
`min-width: 768px` media query, so mobile keeps its existing bottom-sheet
behaviour in both modes. Only surfaces marked `panel-switchable` participate —
the four tab detail views. Confirmation dialogs stay centred in both modes on
purpose: a yes/no question does not belong in a working side panel. **A new
detail surface must carry `panel-switchable`** or it will ignore the setting.

**The budget table is paged; nothing else about the Budget tab is.**
`js/page-size.js` holds the rows-per-page choice (10/20/50/100, default 20 from
`config.js`) in localStorage, set under Account → Appearance and read once when
the trip page loads. Only the table body reads `pagedBudgetItems`; the chart,
totals, bulk selection and every count read `filteredBudgetItems`, so figures
cover every page and "Delete selected" can include rows ticked on another page.
`budgetCurrentPage` clamps, so deleting the last rows of the last page never
strands the table past its end, and the Category filter resets to page 1.

**Every modal and drawer carries `x-dialog`.** `js/dialog.js` registers it
before Alpine starts (from `boot.js`, like the components); put it on the
`role="dialog"` element with the same expression as the backdrop's `x-show`.
While truthy the dialog takes focus, traps Tab, and sets
`body[data-dialog-open]` so the page behind cannot scroll; when it goes falsy,
focus returns to whatever opened it. Dialogs stack — the completion confirm
opens over the checklist drawer — and only the top one traps. It waits two
frames before focusing, so a caller's own `focusWhenShown()` keeps the caret.
**A new dialog without `x-dialog` leaves focus behind its backdrop**, and a
new one without `aria-labelledby` has no name — the same kind of silent opt-in
as `panel-switchable`.

**The trip tabs follow the ARIA tabs pattern.** Each tab is `tab-<id>` and
`aria-controls` its `panel-<id>` section; only the selected tab is a Tab stop,
and Left/Right/Home/End move between them (`onTabKeydown`). `revealTab`
scrolls the bar sideways rather than calling `scrollIntoView`, which would also
scroll the page vertically.

## Localisation

**Every word a member reads lives in a message file, and code refers to keys.**
Three files, three consumers, one rule: English is the default and the
fallback, so a language missing a key shows English, and a key English lacks is
a bug that a test catches first.

- **API** — `planner-api/src/main/resources/messages_en.properties`, read
  through `i18n/Messages`. `ApiException` carries a *key and its arguments*,
  never prose, and `GlobalExceptionHandler` resolves it in the request's
  language — which is why it works from static code (`Slugs`, `TripWindow`)
  that has no `Messages` of its own. Validation annotations say
  `message = "{validation.email.invalid}"`. Filters that write error JSON by
  hand (`CsrfFilter`, `ProxySecretFilter`, `PasswordChangeGate`, the 401 entry
  point) go through `config/FilterErrors`, because they run before MVC and
  never reach the handler. `notFound` takes a whole-sentence key
  (`error.trip.notFound`), not a noun to append "not found" to.
- **Web** — `planner-web/js/i18n/en.js`, read through `t(key, params)`.
  Markup names its words instead of holding them: `data-i18n`,
  `data-i18n-html`, `data-i18n-<attribute>`; Alpine uses `$t` and `$th`.
  Plurals are `key.one` / `key.other` picked by `{count}` through
  `Intl.PluralRules`, never `"item" + (n === 1 ? "" : "s")`.
- **Published pages** — the same `messages_en.properties`, `page.*` keys only,
  inlined as `window.I18N` and read by `page.js`. Only `page.*` is shipped:
  a public file gets what it needs to draw itself, not the API's errors or
  emails (pinned by `onlyThePagesOwnWordsAreShipped`).

**Which language.** `User.languageCode` (nullable, `V12`) — set from Account →
Appearance. `RequestLocale` picks the member's choice, else `Accept-Language`,
else English, and is also Spring's `LocaleResolver`. Null means "follow the
browser", which is different from having chosen English. Three things follow
the language, each by its own rule:

- **Emails** go out in the *recipient's* language; a brand-new invitee has none,
  so they get the inviter's request language (`MailTemplates`, via
  `LocaleContextHolder`).
  **The words are not in `messages_en.properties`.** Each email is a plain
  file, `resources/email/<language>/<name>.txt`: `Subject: …`, a blank line,
  the body, with `{{name}}` where a value goes. Nothing is appended, so the file
  is the email. Only `MailTemplates` reads them. A language or a single file
  that is missing is English. Values are filled in one pass, so a trip called
  `{{password}}` is printed, not expanded, and a line break in a title is
  flattened in the subject. Adding a language is a folder of translated files.
  **Every email is a `MailEvent` and can be switched off in config**:
  `app.mail-events.enabled.<event>` (`MAIL_EVENT_<EVENT>` in the environment,
  default on) for `invited-new-member`, `added-existing-member`,
  `removed-from-trip`, `publish-requested`, `publish-approved`,
  `publish-rejected`, `trip-published` and `payment-recorded`. The gate is
  `EventGatedEmailSender`, the `@Primary` `EmailSender` every service is
  handed, in front of the transport (`TransportEmailSender`: log, file or
  smtp), so no service knows about the switches and a new event needs only an
  enum value, its templates, a `MailTemplates` method and a line in
  `application.yml` and `deploy.sh` (`MailEventGateTest` fails if the yml
  misses one). A misspelt event name fails the start. **Do not switch off
  `invited-new-member`**: it holds the only copy of a new member's temporary
  password. `trip-published` goes to each member separately with their own
  page, only when a trip goes live and not on a re-publish;
  `payment-recorded` goes to the people in the payment who did not record it,
  and a failure there is logged and never undoes the payment.
- **A published page is written in the language of the member it is for**, decided
  at publish time like everything else about whose page it is — a static file
  cannot ask its reader. A personal page uses its member's, not the
  publisher's (`PublishOptions.languageCode`).
- **Text the server writes into a member's own lists** (`ChecklistSeeder`,
  `PlanTemplates`) is stored once in the language of whoever's action created
  it and is ordinary editable text afterwards, like every seeded item. It does
  not change when somebody switches language.

**Guardrails.** `MessageKeysTest` scans the API source: no prose in an
`ApiException`, every key used exists in English, no translation has a key
English lacks, and an apostrophe in a pattern that takes arguments is doubled.
`npm run check` runs `scripts/i18n-check.mjs` (every key used is present and
every key present is used; no toast, message or template literal holds a
sentence) and `scripts/i18n.test.mjs`. `PageStringsTest` fails if an English
sentence comes back into `page.js`. **Both guards are heuristics** — the first
version of the web one missed a plural hiding in a template literal until a
browser run found it — so the test language below is what proves the rest.

**The test language `xx`** exists only in `planner-api/src/test/resources`, so
nothing ships. To see a language end to end, temporarily copy an `xx` into
`src/main/resources` (API) and `js/i18n` (web), every value prefixed
`[xx] `, and anything on screen without the prefix is member data or a string
that was missed. Remove both afterwards.

Things that deliberately stay as they are: `IllegalStateException` texts and
log lines (for operators), country names (English is the reviewed table in
`countries.js`, pinned against the published copy by `npm run check`; every
other language uses the browser's `Intl.DisplayNames`), and month names (the
browser's `Intl`). `<title>` and `<meta>` keep their English in the HTML *and*
carry `data-i18n-*`, because link previews and search engines read the file
without running a script; `npm run i18n:head` writes them and the check keeps
them honest.

## Traps

Each of these cost a real debugging cycle. They are not visible from the code
that breaks.

**The checklist drawer has two closes.** `closeDrawer()` is unconditional and
belongs to paths that have already saved, deleted or navigated (including
`showTab`). Escape, the backdrop and ✕ go through `requestCloseDrawer()`, which
asks first when `drawerDirty`. Wiring a new close control straight to
`closeDrawer()` silently brings back the bug where a half-written note was
thrown away without a word.

**A background tab never runs `requestAnimationFrame`.** `x-dialog` and
`focusWhenShown()` both focus on a frame, so in a tab driven by automation
while another window is in front, "focus moved into the dialog" reads false and
the dialog looks broken. It is not: take a screenshot (which paints a frame) or
bring the tab forward, then check. For the same reason a window resize can
leave `innerWidth` stale in such a tab — for a phone-width check, load the page
in a 400px `<iframe>` on the same origin instead, which shares the session
cookie and lays out for real.

**Object spread invokes getters.** `{...tabFactory()}` *reads* every own
property, so a computed getter is evaluated once — before any data exists — and
frozen. `trip.js` merges tabs with `Object.defineProperties` +
`getOwnPropertyDescriptors` for this reason. Do not "simplify" it to a spread.

**Jackson serialises derived boolean getters.** `isFromPlan()` on a domain object
becomes a `fromPlan` key in the YAML, which then fails to deserialise. Annotate
derived accessors `@JsonIgnore`. `YamlStore` also disables
`FAIL_ON_UNKNOWN_PROPERTIES`, since these files can be hand-edited.

**@JsonIgnore on one accessor kills the whole property.** The flip side of the
trap above. `allDay` had `getAllDay`/`setAllDay` plus a convenience
`@JsonIgnore isAllDay()`; Jackson resolves all three to one property named
`allDay` and the annotation dropped it entirely, so the field never reached the
YAML and a stay's nights came back timed. The fix is to name the convenience
method something that is not an accessor for the field —
`ItineraryItem.coversWholeDay()`. Nothing fails loudly here: the object
round-trips, just without that field.

**`String.format` and `%`.** The published page template contains
percent-encoded text of its own (the favicon data URI), so `StaticSiteRenderer`
substitutes `{{name}}` placeholders literally. Using `.formatted()` there throws
`UnknownFormatConversionException` and publishing silently writes nothing.

**Do not map bare `IllegalArgumentException` to 400.** It was hiding the bug
above behind a message blaming the caller. Deliberate client errors go through
`ApiException.badRequest`; anything else should surface as a 500 with a stack
trace.

**A module script with top-level `await` can finish after `DOMContentLoaded`,**
so a deferred Alpine tag would initialise before the page registered its
component. `js/boot.js` imports Alpine *after* registration instead of racing it.

**`MessageFormat` reads an apostrophe as a quote.** In a message that takes
arguments, `it's` swallows everything up to the next `'`; write `it''s`.
Messages with no arguments are returned as they are, and `page.*` messages are
never formatted (the page fills `{name}` itself), so they take a single `'`.
`MessageKeysTest` fails on the mistake. Arguments are never patterns: a trip
called `50% off {sale}` or `O'Brien` is inserted literally, and the web `t()`
uses a replacer *function* so `$&` in a title is not read by `replace()` as an
instruction.

**A `data-i18n` element is empty until a script runs.** That is the point (no
English in the HTML), and it is also why a page needs `initI18n()` before its
first render and `startTranslating()` after: `renderChrome` does the second, so
every page that draws a header gets it. Elements Alpine creates later (an
`x-for` row, an `x-if` block) carry their markers with them and are filled by
the `MutationObserver` `startTranslating` installs — a one-off pass would leave
those with no language at all. A new page with text and no chrome (`404.html`,
`index.html`) needs its own small module script.

**Two constructors on a class Spring builds need `@Autowired` on the real
one** — see Traps below. `ChecklistSeeder`, `PlanTemplates`,
`TripViewAssembler` and `StaticSiteRenderer` each keep the old constructor,
which uses English, beside the `Messages` one, so tests that build them by
hand did not have to change.

**A `@Bean` method and a `@Service` class cannot share a bean name.** Spring
derives the service's name from its class (`ExchangeRatesClient` →
`exchangeRatesClient`), so a `@Bean RestClient exchangeRatesClient()` collides
with it and startup dies with *"A bean with that name has already been
defined … overriding is disabled"*. `GeocodingConfig` sidesteps this by naming
its client after the provider (`countriesDevClient`, not `geocodingClient`);
`RatesConfig` does the same with `erApiClient`. The injected parameter name has
to match the bean name, so both move together.

**Two constructors on a `@Service`/`@Component` stops the app booting.** Spring
cannot choose between them, falls back to looking for a no-arg one, and fails
with `No default constructor found` — at startup, so it is loud, but the message
names the wrong problem entirely. Both `WeatherClient` and `WeatherService` have
a second constructor taking a `Clock` (a test needs to pin what "today" and
"expired" mean), and both need `@Autowired` on the real one to say which is
which. `AppProperties.Currencies` carries the record-binding version of the same
note.

**The `hidden` attribute loses to any author `display` rule.** It is only a
UA-stylesheet `display: none`, so a component that sets its own `display` — the
published page's `.weather-locations { display: grid }` — stays on screen with
`hidden` dutifully set and `el.hidden === true`. `publish/page.css` now carries
`[hidden] { display: none !important; }` for exactly this; `scss/base` should
too if the planner ever hides by attribute (Alpine's `x-show` is safe, since an
inline style outranks a stylesheet).

Worth knowing how this hid itself: a check written as
`[...els].filter(el => !el.hidden)` **tests the attribute you just set**, so it
reports success while the thing is plainly visible. Assert on a rendered box —
`getBoundingClientRect().height > 0` — when the question is "can the reader see
it".

**A published page is served `Cache-Control: public, max-age=300`,** so a
browser will happily show a five-minute-old copy after a republish. When
verifying a change to `page.js` or `page.css`, add a throwaway query
(`?cb=1`) — and check the page really is the new one (grep it for something the
change introduced) before concluding the change did not work.

**`x-for` leaves its own `<template>` in the DOM, so `:first-child` never
matches a generated item.** Alpine inserts the repeated elements as *siblings
after* the template node, which stays put. Any structural CSS selector counting
from the start of the container is therefore off by one and silently does
nothing — `.card:first-child` matches nothing at all, and `:nth-child(2)` would
"work" only by encoding the quirk. The weather grid wants its first card to
span both columns when the count is odd, which is exactly
`:first-child:nth-last-child(odd)`; that rendered every card half-width instead.
The fix is to let the template state the rule it already knows —
`:class="index === 0 && items.length % 2 === 1 ? 'wide' : ''"` off
`x-for="(item, index) in items"` — and keep the stylesheet matching a plain
class. Nothing errors here: the layout is just quietly wrong.

**`textContent` reads through `x-show`.** It hides with `display: none`, so a
hidden branch is still in the DOM and still in the text. When checking rendered
output (in a browser console or a Playwright assertion), test
`el.offsetParent !== null` per element rather than reading the parent's text —
otherwise a card showing one line looks like it is showing both.

**`<template x-for>` cannot go inside `<svg>`.** The HTML parser hoists it out of
the SVG namespace and Alpine fails on `importNode`. The budget donut writes its
four arcs out explicitly.

**A `<select>` whose options come from `x-for` must not sit inside `x-if`.** The
model is applied before the options exist and the select silently falls back to
its first option. The checklist drawer stays in the DOM for this reason.

**`<input>` with no `type` does not match `input[type="text"]`.**
`scss/base/_forms.scss` includes `input:not([type])` so such a field is never
left completely unstyled.

**Stylesheet links carry `?v=1`.** Bump it when a browser needs to pick up
rebuilt CSS.

**ES modules are not covered by the `?v=` trick.** A page URL query busts the
HTML but not its `import` chain, and `serve.sh` sends no `Cache-Control`, so an
edited `js/` file can keep serving its old body while the HTML around it is
fresh — the symptom is every newly added property reporting "X is not defined"
in Alpine while pre-existing ones still work. `serve.sh` now sends
`no-store`; if you hit it anyway on a server started before that change,
restart the server and close the browser tab (a plain reload is not enough —
the module graph survives it).

**A container test that can't reach Docker is skipped, not failed.** Every
Postgres and MinIO test is `@Testcontainers(disabledWithoutDocker = true)`, so a
build that tested no database still says BUILD SUCCESSFUL. It happened twice
while this was built, for two different reasons: Spring Boot 3.5.3's BOM ships
Testcontainers 1.21.2, whose Docker client Docker Engine 29 refuses ("minimum
supported API version is 1.44") — hence the `testcontainers.version` pin in
`build.gradle.kts`, removable once the BOM carries ≥ 1.21.4; and under Colima
there is no `/var/run/docker.sock` on the host. Colima needs **two** settings
in two places, because Testcontainers reads them from different sources:
`docker.host` from `~/.testcontainers.properties`, but the socket path it mounts
into its cleanup container (`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`) **only from
the environment** — a `docker.socket.override` line in the properties file is
silently ignored, and the cleanup container then fails to mount the Mac-side
path. Without it, every container test reports skipped. Always read the
`skipped` count.

**`DatabaseModeEnvironment` reads the flag before `@DynamicPropertySource`
exists.** It decides which auto-configurations to exclude while the environment
is being prepared, so a test must set `feature-enable-database` through
`@SpringBootTest(properties = …)`, a command-line argument or an environment
variable. Set it in a `@DynamicPropertySource` and the repositories follow the
flag while the auto-configurations don't — a context with JDBC repositories and
no DataSource, or the reverse.

**An explicit NULL does not fall back to a column's default.** `category`,
checklist and budget `status`, the arrays and the like are `NOT NULL DEFAULT …`,
but an `INSERT` that names the column with a NULL value fails the constraint
rather than taking the default. The JDBC mappers therefore write the default
themselves when the domain field is null — budget `status` → `CONFIRMED`,
the same "a row with no status reads as a charge" rule the YAML store follows.

## Documenting APIs

- **Own API:** `docs/api/openapi.yaml` is generated from the controllers
  (springdoc, test scope only) and never hand-edited. After changing a
  controller, route or request/response type, regenerate and commit it;
  `OpenApiDocTest` fails the build if it is stale.

#### Regenerate docs/api/openapi.yaml
```bash
cd planner-api && ./gradlew openApiUpdate
```

- **External services:** `docs/external-apis/`, one hand-written file per
  service. Update a service's file and
  its table row when its client, timeout, TTL, cron, cache rule or trigger
  changes. For a new service, create `<service>.md` (what, auth, base URL and
  config key, each call with trigger and frequency, caching, failure
  behaviour, quirks) and add a table row. Browser-side callers count too — the
  published page calls Open-Meteo itself.

## Scheduled jobs

Documented in `docs/scheduled/`, one hand-written file per job. When adding or changing a `@Scheduled` job, a startup
runner or any recurring background work, create or update
`docs/scheduled/<job>.md`. Each file states what the job does,
its schedule and time zone, **the config key and environment variable that
change the schedule** (and where the default lives), every Java class involved
with its purpose, failure behaviour, and how it behaves on scale-to-zero Cloud
Run. A new job needs a refresh-on-read style fallback, since a cron may never
fire there.

## countries.dev

Free, no key. Three behaviours are load-bearing, all confirmed live and encoded
in `GeocodingClient`:

1. `/cities` answers **404 with plain text `No cities found`** on no match. That
   is an empty result, not a failure.
2. `/cities` has a population floor — Uyuni (10,293) and Ollantaytambo (2,000)
   are missing from it but present in `/places`. Small towns are exactly what a
   trip is made of, so both are queried and merged on `geonameId`.
3. `/places` is the full gazetteer and also returns administrative regions,
   hotels and rivers. Only `featureClass` `"P"` is kept.
4. **Matching is unforgiving about spaces, and fails silently rather than
   emptily.** `Machu Picchu` returns a research base in Antarctica and a hamlet
   in Bolivia, while the Peruvian town — filed as `Machupicchu` — never appears
   at all. `GeocodingClient.placeTerms` therefore also asks `/places` for the
   query with its spaces removed, merging on `geonameId`. Only `/places` gets
   that second term: anything needing the trick is small enough to sit under
   the `/cities` floor anyway.

Some places have no entry under the name travellers use — Peru's Aguas Calientes
is registered as *Machupicchu* — so **an empty result is normal**, free text
always saves, and coordinates stay editable. Never make the lookup a gate.

## Open-Meteo (weather)

Free, no key. Powers the Itinerary tab's weather rows (`weather/` in the API,
`js/weather.js` on the frontend). Nothing is stored — the rows are computed
from the destinations on every request, and readings are cached in memory only.

**Two endpoints, because one is not enough.** The forecast endpoint
(`api.open-meteo.com`) serves a sliding window of about 92 days back to 16 days
ahead and answers **400 for the whole call** outside it — `Parameter
'start_date' is out of allowed range from 2026-06-13 to 2026-09-29`. A trip is
normally booked months out, so *that is the common case*, not an edge case.
Dates outside the window come from the climate endpoint
(`climate-api.open-meteo.com`, CMIP6 downscaled to 10km, daily, 1950-2050)
instead. `WeatherClient` splits a request at the horizon and sends each half to
the endpoint that can answer it.

- **A climate projection is not a forecast, and the UI must keep saying so.**
  The model produces a plausible late-October in Cusco, not next late-October.
  Every row carries `DayWeather.Source` (`FORECAST` / `CLIMATE` /
  `UNAVAILABLE`), which the page renders as a "Forecast" or "Typical" badge.
  Dropping that distinction would invite somebody to pack from a number that
  cannot mean what it looks like it means.
- **The climate endpoint serves no `weather_code`.** It accepts the parameter
  and answers with a column of nulls, so the condition and icon are derived
  from `precipitation_sum` for those rows (`js/weather.js`). Reading a null
  column as `0` would be worse than useless — 0 is a real WMO code meaning
  "clear sky", so it would invent a sunny day out of no data.
- **One call per trip, never one per destination.** Both endpoints take
  comma-separated coordinate lists and answer with a JSON array aligned to the
  order asked, so a whole trip costs at most two outbound calls. This is not an
  optimisation: three back-to-back calls is enough to get
  `429 "Too many concurrent requests"`.
- **A single coordinate answers with a bare object, not a one-element array.**
  Both shapes are parsed, or a one-destination trip would be the only one that
  never showed weather.

**The debugging trap, which cost most of a session.** When the climate endpoint
throttles, it does not answer at all: TCP connects in 40ms, the request is
accepted, and nothing arrives until the socket times out. Meanwhile `curl`
against the identical URL returns 200 in 200ms. That gap invites a series of
wrong diagnoses, and every one of these was investigated and ruled out — IPv6
(both resolved addresses connect; `preferIPv4Stack` changes nothing), TLS (a
hand-written GET over an `SSLSocket` got 200 in 60ms), HTTP/1.1 vs HTTP/2, the
`User-Agent`, and individual query parameters. The tell is that the *same* URL
alternates between 200, 429, `500 "Something went wrong."` and silence purely
on how recently anything asked, and that `curl` only looks immune because it
gets run as isolated one-offs with gaps between them. **So: a read timeout here
means throttling, not a slow model, and not a bug in the request.** Test
against canned responses (`CannedHttp`) rather than the live service — live
calls in a suite fail intermittently depending on what ran before them.

**Caching, and when a lookup happens.** Readings are stored — the fifth
per-trip entity, `data/travels/weather/<slug>.yml` through
`WeatherRepository extends TripScopedYamlRepository`. `WeatherClient` is a
stateless fetcher; `WeatherService` owns the cache, because a cache has to be
scoped to a trip to be stored and deleted with one.

This is a deliberate exception to "derived values are never stored", and the
reason it is safe is worth keeping straight: that rule exists so nothing on disk
can go quietly stale against what it was computed from. A `WeatherRecord`
carries both halves of its own provenance — the coordinates it was fetched for
and the instant it was fetched — so it can always say whether it is still valid.
A stored nights count could not.

Three rules decide whether a day costs a call:

- **A past date with a record is never looked up again.** The weather on 25
  October stops changing once that day is over. What is frozen is whatever was
  last held — usually a climate projection — still carrying its `source`, so a
  preserved guess never poses as a measurement.
- **A past date with no record is fetched once**, then falls under the rule
  above forever. "Past" is a reason not to ask *again*, not a reason never to
  ask; a trip entered after the fact would otherwise never show weather.
- **A present or future date** is refetched once its record passes
  `app.weather.cache-ttl` (12h), since a forecast for a day still to come
  genuinely changes.

Only the points with a gap are asked about, so adding one destination costs a
lookup for that destination rather than a refetch of the other four. Records are
keyed by **rounded coordinates and date**, not by destination id — two
destinations at the same place share a record, renaming one keeps its weather,
and moving one to new coordinates correctly stops matching instead of silently
describing somewhere else. An expired record that fails to refresh is served
stale rather than blank; by then the lookup has already failed, and half-day-old
numbers still tell somebody what to pack.

**Readings are only written to the store while the trip is under way** — from
the trip's own start date to its end date, inclusive (`WeatherService.isUnderWay`).
Before that a lookup still happens, because a trip is planned months ahead and
the page needs something to show, but the result is held in the process's
memory only (`unsaved`), so a forecast that will be replaced a dozen times
before anyone travels never costs a row. After the end nothing new is written
and what was stored during the trip stays frozen under rule 1. A trip with no
dates of its own is never under way. The price is that the in-memory copy dies
with the process, so a cold start asks again for a trip not yet under way.

The trigger is the frontend's `reload()`, so the lookup happens when a **trip**
is opened rather than when the Itinerary tab is. `loadWeather()` compares a
signature of the destinations (id, name, dates, coordinates), so a checklist
tick re-triggers nothing while adding a destination refetches. There is no
`@Async` prewarm on the server — the frontend already had the trigger.

Because a lookup can always fail, it is never allowed to break the page:
failures come back as no readings, the day still appears with its location, and
the card says `📅 Forecast not yet open` (a date beyond the horizon) or
`📅 Weather unavailable` (a near date whose lookup failed) — two different
situations that deserve different words. The weather request is deliberately
**not** part of `GET /trips/{id}` — that bundle is refetched after every
mutation and must not wait on somebody else's server.

**The published page looks weather up in the reader's own browser**
(`resources/publish/page.js`, `loadWeather`). It cannot be baked in at publish
time: a static page has no API behind it and may be opened months later, so a
stored forecast would ship something guaranteed to be stale. `PublishedTrip.Day`
therefore carries `places` with coordinates, not readings. The same two
endpoints and the same "typical vs forecast" labelling apply, and the same
batching discipline — one request per endpoint for the whole page, coordinates
de-duplicated across days, never one per card. Load scales with readers rather
than concentrating on the server, which is the one good thing about having no
API there.

**Details: more of the same answer, no more requests.** Each card also shows
sunrise/sunset, UV, rain chance, wind and feels-like, and a tooltip carries the
rest (rain and snow amounts, humidity, cloud cover, daylight, wind direction).
They ride the existing two calls as extra `daily=` values, chosen from one
catalogue, `weather/DetailField`, which drives the request, the parsing and —
through `WeatherDetailsDriftTest` — what `page.js` must ask for.

- **One document, not columns.** `WeatherRecord.details` is a map keyed by
  `DetailField.key()`: `jsonb` in Postgres (`V11`), a nested mapping in YAML, so
  the next field is a line in the catalogue and a chip, not a migration.
  `TripScopedJdbcRepository` takes a `jsonColumns` set to bind it (text cast to
  `jsonb` in the statement, like `users.published_page`).
- **Absent means unknown, and 0 is an answer.** A null column leaves the key
  out; a real 0 (0 mm, 0 % chance, 0 km/h) is kept and shown. Every frontend
  test is `!= null`, never truthiness — the same trap as WMO code 0.
- **`/climate` is only asked what it can answer.** Verified live for a Cusco
  date: it serves sunrise, sunset, daylight, rain, snowfall, wind, gusts,
  humidity and cloud cover, and answers **null** for UV, feels-like, rain
  probability, wind direction, sunshine and showers. Those are `inClimate =
  false`, so a climate card just has fewer chips. Recheck if the model changes.
- **`timezone=auto` on both calls**, or sunrise and sunset come back in UTC
  (Cusco 05:16 read as 10:16). The times are destination-local wall-clock.
- **An old record has no `details`** and shows exactly as before; it is filled
  in when its own TTL lapses, never by a forced refetch.
- **Today has its own TTL.** `app.weather.today.cache-ttl` (`TodayWeatherProperties`,
  default 12 h) governs a reading for today's date; `app.weather.cache-ttl`
  governs later days; past days are never refetched. Both are 12 h, so the
  split changes nothing until somebody sets one.
- **Air quality is left out on purpose:** a separate host (a third request),
  hourly only and forecast-only.

## Exchange rates (open.er-api.com)

Free, no key. One table for the whole install in `data/rates.yml`, refreshed on
a cron and read by `BudgetService` for every conversion. The `rates/` module
mirrors `weather/`: a stateless client, a service that holds the table, a
repository, a scheduler.

**Why not an ECB feed.** frankfurter and the other obvious free choices carry
about thirty currencies, all majors. This app is for trips, and a trip's costs
land in PEN, BOB, VND — exactly what those omit. open.er-api.com answers with
166, filtered down to `app.currencies.available` on the way in so the file stays
readable and no code nobody can pick ever appears in it.

- **The base is the anchor and it is not the trip's display currency.** Every
  rate is "units per one unit of `base`" (EUR by default), and the base is
  stored as `1` so no currency is a special case. This is what let
  `TripService.rebase` be deleted: rates used to be quoted against each trip's
  own display currency, so changing that currency silently invalidated every
  stored number and had to re-quote the table. Now the display currency anchors
  nothing and is purely a label.
  - The consequence inside `BudgetService`: `anchor` used to mean both "the rate
    pivot" and "what an item with no currency is in". Those are now
    `pivot` (the table's base) and `tripCurrency`, and conflating them again
    would make an untagged expense read as EUR.
- **Fetched once at startup on its own thread**, then on `app.rates.cron`
  (default `0 30 1 * * *` UTC, an hour after the provider's own daily update —
  asking more often returns identical numbers). The startup fetch is
  deliberately off the boot thread: the provider is somebody else's server and
  booting must never wait on it, let alone fail on it. `RatesRefresher` hands it
  to `CompletableFuture.runAsync`; until it lands, `RatesService.current()`
  answers from `data/rates.yml`, which after any normal restart is yesterday's
  table rather than nothing.
- **…and also refreshed on read once older than 24 h.** On free-tier Cloud Run
  the CPU exists only while a request is running, so neither the 01:30 cron nor
  the startup thread can be relied on. `RatesService.current()` therefore
  refreshes a table that is stale (older than `MAX_AGE`, or never fetched),
  synchronously, with the client's own timeout. **This is the one place a
  request can wait on the provider**, so it is fenced three ways: a `tryLock`
  means only one reader fetches while every other reader gets the held table at
  once; a failure (from a read or the cron) stops reads retrying for
  `RETRY_AFTER_FAILURE` (15 min), or an outage would add the whole timeout to
  every request; and a failed fetch still keeps the old table. The cron and the
  startup fetch are unchanged and take the same lock.
- **A failed refresh never clears what is held.** The client returns null and
  the service keeps the previous table. Rates a day old are a rounding
  difference; no rates at all is every total on the install reading as
  unconvertible.
- **The provider answers `200` with `result:"error"`** for a bad base, so the
  status code alone cannot tell success from failure — `Response.failed()`
  checks the body.
- **Read-only everywhere.** There is no write endpoint and no editor: the
  per-trip `PATCH` and the Exchange-rates dialog are gone, and
  `Trip.exchangeRates` is no longer read (the field stays so older files still
  load). The budget panel shows a one-line note instead, and the budget view
  carries `ratesBase`/`ratesDate` so the page can pivot and say where its
  numbers came from.
- **The currency list is app.yml only**, and it is `app.currencies.available` —
  already the one catalogue the whole app selects from. Note the hardcoded
  fallback inside `AppProperties.Currencies` is majors-only, so a deployment
  that omits the list gets no LATAM currencies at all.

## Deployment: Cloud Run + Neon + Cloudflare

Built to stay inside three free tiers. The runbook is `docs/deploy.md`; the plan
and every decision's reasoning are in
`.claude/plans/2026-09-18-postgres-and-cloud-run.md`.

```
yourdomain.com  (Cloudflare DNS + TLS)
 ├─ /*       Cloudflare Pages → planner-web/dist        static, unmetered
 ├─ /api/*   Pages Function → Cloud Run (API)           + X-Proxy-Secret
 └─ /p/*     Pages Function → R2 (published pages)      never wakes the API
Cloud Run  europe-west3 · 0–1 instances  ──►  Neon Postgres  aws-eu-central-1
```

- **One origin is the requirement; one container is not.** The auth cookies are
  host-only and `SameSite=Lax`, and `js/api.js` reads the CSRF cookie from
  `document.cookie` to echo it as `X-XSRF-TOKEN`. Split across two hosts, a page
  on `app.example.com` cannot read a cookie set by `api.example.com`, so every
  write fails CSRF; across `pages.dev` and `run.app` (different sites — `run.app`
  is on the Public Suffix List) the login cookie isn't even sent. **Local
  development only works split because browsers don't separate cookies by
  port** — `localhost:3000` reads what `localhost:8080` set — which is exactly
  why it can't be copied to production. The proxy Function makes the browser see
  one origin, so no auth code changed. (Firebase Hosting rewrites were ruled out
  for the same reason: its CDN strips every cookie except `__session`.)
- **The proxy** (`functions/api/[[path]].js`) forwards method, body, query and
  cookies untouched, drops any client-sent `Forwarded`/`X-Forwarded-*` headers
  before setting its own (the API trusts them — `forward-headers-strategy`),
  and adds `X-Proxy-Secret`. **`ProxySecretFilter`** in the API refuses anything
  without that secret (403 `proxy_required`), except `/actuator/health`, so the
  public `run.app` URL isn't a second front door around Cloudflare. With
  `PROXY_SECRET` blank — local development — it does nothing.
- **`config.js` defaults the API base to the page's own origin**, except on
  `localhost:3000`, where it stays `http://localhost:8080`. `?api=` and
  `window.PLANNER_API_BASE` still override.
- **`npm run build` copies the deployable site into `dist/`**, including
  `_headers` (`Cache-Control: no-cache` on everything, so browsers revalidate —
  the ES-module trap below, defused for the CDN) and `404.html` (so unknown
  paths are a real 404, not Pages' fallback to `index.html`).
  `npm run pages:dev` runs the whole thing locally through wrangler on :8788.
- **The image is API-only** (`planner-api/Dockerfile`): extracted into layers,
  with a class-data-sharing archive written by a training run in the image, which
  cut cold start from ~8.9 s to ~6.7 s at 1 CPU / 1 GiB. It runs as a non-root
  user. **`.dockerignore` is a security list** — `data/` holds password hashes,
  the JWT signing key and every trip.
- **Scale to zero shapes three things:** cold starts on the first request after
  a quiet spell; the rates refresh-on-read above; and `JWT_SECRET` must come
  from Secret Manager, because a generated one lives in the data directory,
  which Cloud Run discards on every restart — logging everybody out.
- **Neon:** the *direct* endpoint, not `-pooler` (Flyway holds a session lock the
  transaction-mode pooler can't), `sslmode=require`, and Hikari `minimum-idle:
  0` — an idle pool holding connections would stop Neon sleeping and spend its
  monthly compute hours while nobody uses the app.
- **New settings go in their own `@ConfigurationProperties` record**
  (`ProxyProperties`, `R2Properties`, `ImportProperties`), each registered on its
  own config class — never into `AppProperties`, which about twenty tests
  construct positionally.

**The importer** (`importer/`) is how YAML data reaches Postgres — once, as
application code, never as a Flyway migration. It is the API started with
`--app.import.yaml-dir=<data dir>` in database mode: `ImportRunner` runs ahead
of the bootstrap-owner runner (`HIGHEST_PRECEDENCE`), imports, prints a report
and **exits the process** (0 committed or dry run verified, 1 failed, 2
refused), so the bootstrap never runs on an import start. The rules, all in
`YamlImporterTest`:

- **One transaction around everything**; the repositories' own transactions
  join it, so any failure leaves the database as empty as it was. `--app.import.dry-run=true`
  does the whole thing and rolls back.
- **Empty or nothing.** `users` and `trips` are locked, then must both be
  empty. It never merges.
- **Read through the YAML repositories, written through the JDBC ones**, so it
  sees exactly what the app sees in YAML mode — rows carrying the pre-country
  `destinationIds` link import *as the app shows them today* (it doesn't read
  that link either) and are counted in the report, not converted.
- **Each trip's rows go in with `replaceAll` in file order**, which is what
  gives `seq` the YAML's order.
- **History survives.** The user and trip repositories stamp timestamps on
  save; right after each save an `UPDATE` restores the file's own values.
  (Which is also why `YamlImporterTest` writes its fixtures straight through
  `YamlStore`: written through the repositories, every timestamp would read
  "now", and a restore could not be told from an overwrite.)
- **`ImportVerifier` proves the copy inside the transaction**, before it can
  commit: every record field by field, in order, via the same Jackson mapper
  YAML is written with (exact decimals, instants to the microsecond); and every
  member's `BudgetService` summary — shares, settlements and all — recomputed
  from both stores against one fixed rate table. Any difference rolls back.
- Weather (a cache), exchange rates (refetched) and rendered pages (files, not
  rows) are deliberately not imported; the report lists the trips to publish
  again.

The procedure is `docs/deploy.md` Part 9, Option B.

## Conventions

- Boot 3.5 with Jackson 2 is deliberate: YAML is still a persistence layer (the
  local store, and what the importer reads), and Jackson 3's relocation to
  `tools.jackson.*` was unverified. The sibling
  `../travel-app` uses Boot 4.1; switching is a two-line change in
  `build.gradle.kts`.
- YAML files are meant to be readable next to the hand-written ones in
  `../josephinealinea.github.io/_data/travels/` — ISO dates, no document
  markers, optional fields simply absent (`NON_NULL`).
- Category keys (`transport`, `lodging`, `activities`, `shopping`, `food`,
  `other`) match that site's `budget_categories.yml`, so published pages reuse
  its icons and colours. `ChecklistCategory` is the enum, shared by checklist,
  itinerary and budget rows, and **its declaration order is the order every
  picker, filter and legend shows** — so adding one in the middle moves it
  everywhere at once. Adding a category means: the enum, `PublishStyle`'s two
  maps, `CATEGORIES` in `js/format.js`, and an arm in `PlanTemplates` — that
  last one is a `switch` with no `default`, so the compiler names it for you.
  Take the icon and colour from `budget_categories.yml` rather than choosing
  one, or the same category looks different on a published page.
- Exchange rates are fetched daily for the whole install — see the Open-Meteo
  section's neighbour below. A currency with no rate is still reported in
  `currenciesMissingRates` and excluded from the total, never counted at 1:1.
- Errors are RFC-7807 `ProblemDetail` with a machine-readable `code`, plus an
  `errors` field-to-message map on validation failures — the shape
  `js/api.js` parses.
