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
  after start. That null is what makes the seeded item fall back from
  `Plan 6N accommodation in Cusco` to `Plan accommodation in Cusco`.
- **Seeded items are ordinary items once created.** Renaming a destination does
  not rewrite their text; `autoSeeded` is metadata only.
- **Deleting a destination unlinks its checklist items, it does not delete
  them.** Losing a place must never discard the planning done against it.
- **Adding a plan never completes a checklist item.** Only an explicit
  `PATCH /checklist/{id}/status` does. That is what "Plan another" exists for,
  and force-completing with zero plans is allowed.
- **`BudgetSync` is one-way and narrow.** A later cost change updates only
  amount and currency, so a description someone has since corrected in the
  budget is never clobbered. Clearing the cost, or deleting the plan, removes
  the row it created. A manual expense has no `itineraryItemId` and is never
  touched.

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
and data all inlined, no external requests — from `resources/publish/page.css`
and `page.js`. Those assets live in the API on purpose, so publishing never
depends on the frontend having been built. Output is a plain static directory;
copying it to a CDN is the whole deployment.

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
each page's head. Add it to `THEMES` in `StaticSiteRenderer` too if published
pages should offer it.

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

Some places have no entry under the name travellers use — Peru's Aguas Calientes
is registered as *Machupicchu* — so **an empty result is normal**, free text
always saves, and coordinates stay editable. Never make the lookup a gate.

## Conventions

- Boot 3.5 with Jackson 2 is deliberate: YAML *is* the persistence layer, and
  Jackson 3's relocation to `tools.jackson.*` was unverified. The sibling
  `../travel-app` uses Boot 4.1; switching is a two-line change in
  `build.gradle.kts`.
- YAML files are meant to be readable next to the hand-written ones in
  `../josephinealinea.github.io/_data/travels/` — ISO dates, no document
  markers, optional fields simply absent (`NON_NULL`).
- Category keys (`transport`, `lodging`, `activities`, `other`) match that site's
  `budget_categories.yml`, so published pages reuse its icons and colours.
- Exchange rates are hand-maintained per trip. A currency with no rate is
  reported in `currenciesMissingRates` and excluded from the total, never
  counted at 1:1.
- Errors are RFC-7807 `ProblemDetail` with a machine-readable `code`, plus an
  `errors` field-to-message map on validation failures — the shape
  `js/api.js` parses.
