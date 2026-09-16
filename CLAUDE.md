# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Two independent projects: `planner-api` (Spring Boot 3.5 / Java 21, no database)
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
.claude/plans/      implementation plans
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
cd planner-api && PORT=8090 PUBLIC_BASE_URL=http://localhost:8090/p ./gradlew bootRun
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

There is no linter and no frontend test suite. Failures land in
`planner-api/build/reports/tests/test/index.html`, and the machine-readable
detail is in `build/test-results/test/*.xml`.

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
  adding a time later starts showing it.
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
- **The budget rollup is computed twice, and the two halves never mix.**
  `BudgetService.Summary` carries `charged` and `forecast`, each a whole
  `Breakdown` — category slices, country slices, native totals, total and
  missing rates over one set of rows. Group by chooses between them
  (`budgetView` in `js/pages/trip/budget.js` resolves it in one place) and
  every number on the panel follows together, which is the point: a total from
  one set of rows shown above a breakdown of another is the single way this
  panel can lie. The headline says which it is — `Total:` or `Forecast
  total:` — rather than leaving a reader to infer it from the selector.
- **The trip's dates bound every date recorded against it.** `TripWindow`
  (`trips/api`) is the one check, applied in `DestinationService`,
  `ItineraryService` — which is also what the checklist's Plan form writes
  through — and `BudgetService`. Both ends are inclusive, and only a value the
  caller actually *sends* is checked, so narrowing a trip's own dates never
  makes an existing row uneditable. The frontend mirrors it with `min`/`max` on
  every date input plus `dateOutsideTrip()` in `js/pages/trip.js`; nothing
  retro-validates what is already stored.

## Backend architecture

Feature modules, each with the same four layers — `domain/` (stored records),
`api/` (services, where the rules live), `infra/` (repository interface plus its
YAML implementation), `web/` (controllers and request/response types).

**Storage.** Every repository is an interface with a
`@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false",
matchIfMissing = true)` YAML implementation, so a database implementation drops
in without touching a service. None exists yet, and `FeatureFlags` fails startup
with an explanation rather than letting the flag silently do nothing.

- `TripScopedYamlRepository` holds the shape shared by destinations, checklist,
  itinerary and budget: one YAML list per trip, replaced wholesale under that
  trip's lock. Adding a fifth per-trip entity means extending it, not copying it.
- **A trip's file is the single source of truth.** `trips/index.yml` is only an
  id-to-slug directory, so there is no duplicated trip data to fall out of sync.
- **Deleting a trip must delete everything it put anywhere.** The five per-trip
  YAML files go in `YamlTripRepository.delete`; the *rendered public page* goes
  in `TripService.delete`, via `renderer.remove(slug)`. That second one is the
  easy cascade to forget and the only one where forgetting is a privacy
  problem rather than a tidiness one: a published page is a plain static
  directory, so with no trip behind it nothing would ever remove it and it
  would keep serving the whole plan at its public URL. Three orphaned
  directories were found doing exactly that. Pinned by
  `AuditTrailTest.deletingATripRemovesItsPublishedPage`.
- `TripLocks` guards read-modify-write; `YamlStore` writes to a `.tmp` and moves
  it into place. Both are process-local, which is why this is **single-instance
  only**.
- **Derived values are never stored.** Nights are recomputed from the dates, in
  Java for the seeder and in JS for the destinations table.

**Permissions** live entirely in `TripAccessService` — `requireMember` or
`requireOwner` at the top of each service method. Non-members get 404, not 403,
so trip ids cannot be probed. Owner-only means exactly two things: deleting the
trip, and publishing it.

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

**Accounts only exist via invitation.** `UserService.findOrCreate` is called when
a member is added, so every invited member has an account immediately. That is
what makes the display rule work without a separate "pending invite" concept:
`screenName != null ? screenName : email`.

**Publishing** renders one self-contained HTML file per trip — stylesheet, script
and data all inlined, no external requests except the reader's own weather
lookup — from `resources/publish/page.css` and `page.js`. Those assets live in
the API on purpose, so publishing never depends on the frontend having been
built. Output is a plain static directory; copying it to a CDN is the whole
deployment.

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
from the *publishing* member at publish time — so ticking a box applies to the
next publish, not retroactively, because a published page is a rendered file.
`AuthDtos.PublishedPage` groups them and `PATCH /account/published-page` takes
only the flags being changed, so the next checkbox is one field in three
places rather than a new endpoint. There are three:
`publishItineraryCost`, `publishDestinationDays` and
`publishForecastExpenses` — the last gating whether a published page offers
its two Forecast options under Group by at all. **The planner's own Budget tab
always offers all four**; the setting is about what a public page reveals, and
what a trip is still going to cost is a more private number than what it has
cost so far. `PublishOptions` carries them into
`StaticSiteRenderer`, so `render` does not grow a boolean parameter per
feature — `render(trip, false, true)` says nothing about which is which.

The rule that matters: **"not displayed" has to mean "not shipped".** A
published page is public, so a value left in `window.TRIP` is readable by
anyone who opens the source even when nothing renders it — hiding with CSS or
JS would be theatre. `StaticSiteRenderer` therefore leaves a gated value out of
the snapshot, and the test asserts the number appears nowhere in the file
rather than just nowhere in the markup. The payload's mapper is not
`NON_NULL`, so what a reader sees is the key with a `null` — the figure itself
is absent, which is what matters; `page.js` gates on the falsy value
(`budget.forecast`, `destination.nights`) exactly as it would on a missing
key. `publishItineraryCost` is a
primitive `boolean` for the same kind of reason: `YamlStore` serialises
NON_NULL, so a `Boolean` would be absent from `users.yml` until first set, and
a setting you cannot see in the file is one nobody knows is there.

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
their next load, and `StaticSiteRenderer.safeTheme` does the same for a trip
whose stored `publishedTheme` is no longer offered.

**Detail views are popup or side panel, by setting.** `js/panel-mode.js` stores
the choice and puts `panels-popup` or `panels-side` on the body;
`scss/components/_panel-mode.scss` swaps the geometry inside a
`min-width: 768px` media query, so mobile keeps its existing bottom-sheet
behaviour in both modes. Only surfaces marked `panel-switchable` participate —
the four tab detail views. Confirmation dialogs stay centred in both modes on
purpose: a yes/no question does not belong in a working side panel. **A new
detail surface must carry `panel-switchable`** or it will ignore the setting.

## Traps

Each of these cost a real debugging cycle. They are not visible from the code
that breaks.

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

## Conventions

- Boot 3.5 with Jackson 2 is deliberate: YAML *is* the persistence layer, and
  Jackson 3's relocation to `tools.jackson.*` was unverified. The sibling
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
