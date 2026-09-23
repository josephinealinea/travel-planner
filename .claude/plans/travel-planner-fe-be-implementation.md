# Travel Planner — FE + BE Implementation Plan

## Context

`https://josephinealinea.dev/travel/2026-latam-trip/` is a **read-only, hand-authored**
trip page. Everything it renders comes from YAML that a human edits by hand in
`josephinealinea.github.io/_data/travels/{about,locations,checklist,itinerary,budget}/<trip-key>.yml`,
rendered by `_layouts/travel.html` (727 lines of Liquid) with panel toggles
(About / Weather / Itinerary / Checklist / Budget), a theme selector, and live
Open-Meteo weather.

The goal is to replace the hand-editing with a **collaborative planning app** that produces
that same public page as its output. The unit of work is the **checklist**: adding a
destination seeds three checklist items, planning one produces itinerary entries, and
entering a cost on a plan produces a budget record. When the trip is good enough to share,
the owner publishes a static page.

Target directory `/Users/joeydevivre/Documents/GeekPOC/Personal Site/travel-planner` is
empty (only `.idea/`) and is **not** a git repo yet.

### Decisions locked in

| Question | Answer |
|---|---|
| Name for "places/locations" | **Destination** (`Destination`, `/destinations`) |
| Publish target | **Self-contained static HTML** → `data/published/<slug>/index.html` + `trip.json`, served at `/p/{slug}` |
| Codebase | **Fresh** in `travel-planner/`, borrowing patterns from `../travel-app` |
| Lat/lon source | **countries.dev** — autocomplete on typing, **and** editable by any member at capture time or later |

### Reference material (read before starting)

- `../travel-app/travel-web/js/api.js` — fetch wrapper with cookie auth, CSRF double-submit,
  single-flight 401 refresh. **Port this near-verbatim.**
- `../travel-app/travel-api/src/main/java/com/josephinealinea/travel/` — feature-module package
  layout (`config/ shared/ identity/ trips/ itinerary/ checklist/ publicview/`), each with
  `domain/ api/ infra/ web/`. **Mirror this shape.**
- `../josephinealinea.github.io/assets/js/theme-selector.js` — `THEME_REGISTRY` + `<link disabled>`
  toggling + `localStorage.selectedTheme` + `body.theme-*`. **Port this mechanism.**
- `../josephinealinea.github.io/_layouts/travel.html` — panel-toggle markup and the
  todo-first checklist sort; the published page should feel like this.
- `../josephinealinea.github.io/_data/travels/budget/2026-latam-trip.yml` — the
  `display_currency` + `exchange_rates` + `items[]` pattern to copy for budget.

---

## Verified external API: countries.dev

No API key, no sign-up. Confirmed live during planning:

```
GET https://countries.dev/cities?q=cusco&limit=8
[{"geonameId":3941584,"name":"Cusco","asciiName":"Cusco","countryCode":"PE",
  "admin1Code":"08","latitude":-13.53188,"longitude":-71.96701,
  "population":428450,"timezone":"America/Lima","featureCode":"PPLA"}]

GET https://countries.dev/places?q=ollantaytambo&limit=20   # 12M-entry gazetteer
[{"geonameId":3934055,"name":"Ollantaytambo","featureClass":"P","featureCode":"PPLA3",
  "countryCode":"PE","latitude":-13.25722,"longitude":-72.26306,"population":2000, ...}]

GET https://countries.dev/alpha/PE
{"name":"Peru","flag":"🇵🇪","currencies":[{"code":"PEN",...}],"timezones":["UTC-05:00"], ...}
```

**Three gotchas that must be handled:**

1. `/cities` returns **HTTP 404 with plain text `No cities found`** on no match — this is an
   empty result, *not* an error. Do not let it bubble up as a 500.
2. `/cities` has a population floor. `Uyuni` (pop 10,293) and `Ollantaytambo` (pop 2,000) are
   **absent from `/cities`** but present in `/places`. Small towns are exactly what a trip needs.
3. `/places` mixes in admin regions (`featureClass:"A"`), hotels (`"S"`), rivers etc. Filter to
   `featureClass == "P"` (populated place).

→ **`GeocodingClient` strategy:** call `/cities?q=&limit=8` and `/places?q=&limit=20` (filter
`featureClass=="P"`), merge on `geonameId`, sort by population desc, cap at 8. Treat 404 as
empty. Enrich `countryCode` → country name + flag emoji + currency via `/alpha/{code}`, cached
in a `ConcurrentHashMap` (250 countries, immutable — cache forever).

Some real destinations have **no gazetteer match at all** (Peru's "Aguas Calientes" is
registered as *Machupicchu*). This is the reason manual lat/lon entry is required, not optional.

---

# PART A — Backend (`travel-planner/planner-api`)

## A1. Tech stack

| Concern | Choice |
|---|---|
| Language / build | Java 21 (Temurin 21.0.10 installed), Gradle Kotlin DSL + wrapper 8.14.3 |
| Framework | **Spring Boot 3.5.x** |
| Web | `spring-boot-starter-web`, `-validation` |
| Security | `spring-boot-starter-security` + `-oauth2-resource-server` (Nimbus JWT encode/decode, no third-party JWT lib) |
| Persistence | **`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`** (version managed by the Boot BOM) |
| Email | `spring-boot-starter-mail` (only active when `app.mail.mode=smtp`) |
| HTTP client | `RestClient` (built into Boot 3.2+) for countries.dev |
| Test | `spring-boot-starter-test`, `spring-security-test` |
| Database | **none** — see A3 |

**On Boot 3.5 vs 4.1:** `../travel-app` uses Boot 4.1 / Jackson 3. Persistence here is
*entirely* YAML, so the Jackson-YAML artifact is load-bearing. Boot 3.5's BOM manages
`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` with no explicit version and no
coordinate risk, whereas Jackson 3 relocated to `tools.jackson.*` — I could not verify those
coordinates offline (Maven Central search timed out). Flipping to 4.1 later is a two-line change
in `build.gradle.kts`; say the word if you'd rather match `travel-app` from day one.

## A2. Package layout — `com.josephinealinea.planner`

Feature modules, each `domain/ api/ infra/ web/` (mirrors `travel-app`):

```
config/         AppProperties, FeatureFlags, SecurityConfig, JwtService,
                JwtCookieAuthFilter, AuthCookies, CorsConfig
shared/         ApiException, GlobalExceptionHandler (ProblemDetail), Slugs, Ids,
                Nights (date math), Money
storage/        YamlStore<T>, YamlPaths, StorageProperties, TripLocks
identity/       domain/User · api/AuthService,UserService · infra/UserRepository
                web/AuthController, AccountController, AuthDtos
trips/          domain/Trip,TripMember,TripRole,TripStatus · api/TripService,TripAccessService
                infra/TripRepository · web/TripController, MemberController, TripDtos
destinations/   domain/Destination · api/DestinationService, ChecklistSeeder
                infra/DestinationRepository · web/DestinationController
checklist/      domain/ChecklistItem,ChecklistCategory,ChecklistStatus · api/ChecklistService
                infra/ChecklistRepository · web/ChecklistController
itinerary/      domain/ItineraryItem · api/ItineraryService, PlanTemplates, BudgetSync
                infra/ItineraryRepository · web/ItineraryController
budget/         domain/BudgetItem · api/BudgetService · infra/BudgetRepository
                web/BudgetController
geocoding/      GeocodingClient, PlaceSuggestion, CountryCatalog · web/GeocodingController
notification/   EmailSender, LoggingEmailSender, FileEmailSender, SmtpEmailSender, MailTemplates
publish/        domain/PublishRequest · api/PublishService, StaticSiteRenderer
                web/PublishController, PublicPageController
```

## A3. Storage — the `feature-enable-database` flag

```yaml
# application.yml
feature-enable-database: false      # default OFF — YAML file store
app:
  storage:
    root: ${DATA_DIR:./data}
```

Every repository is an **interface** in `<module>/infra/`. Two implementations, selected by the
flag — so turning it on later swaps the layer with no service/controller change:

```java
@Repository
@ConditionalOnProperty(name = "feature-enable-database",
                       havingValue = "false", matchIfMissing = true)
class YamlTripRepository implements TripRepository { ... }
```

A `jpa/` sub-package is created per module with a `package-info.java` documenting the contract,
but **no JPA implementation is written in this phase**. `FeatureFlags` fails fast at startup with
a clear message if `feature-enable-database=true`, so the flag can never silently do nothing.

### File layout (mirrors `_data/travels/<entity>/<trip-key>.yml`)

```
data/
  users.yml                              # all users, one file
  trips/index.yml                        # id, slug, title, dates, ownerId, status
  travels/
    trip/<slug>.yml                      # trip + members[] + publishRequests[]
    destinations/<slug>.yml
    checklist/<slug>.yml
    itinerary/<slug>.yml
    budget/<slug>.yml
  published/<slug>/{index.html,trip.json}
  outbox/<timestamp>-<to>.eml            # dev email sink
```

### `YamlStore<T>` requirements

- Jackson `ObjectMapper(new YAMLFactory())` + `JavaTimeModule`; disable
  `WRITE_DATES_AS_TIMESTAMPS` and `WRITE_DOC_START_MARKER`; `NON_NULL` inclusion so optional
  fields stay out of the file.
- **Atomic writes**: serialise to `<file>.tmp`, then `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
  A crash mid-write must never leave a half-written trip.
- **Locking**: `TripLocks` holds a `ConcurrentHashMap<String, ReentrantReadWriteLock>` keyed by
  trip slug; `users.yml` and `trips/index.yml` get their own locks. Read under the read lock,
  read-modify-write under the write lock.
- **Single-instance only.** Document this in the README — no clustering without the DB flag.
- Missing file → empty collection, never an exception.

## A4. Domain model

**User** — `id` (UUID), `email` (lowercased, unique), `screenName` (nullable),
`passwordHash` (BCrypt), `mustChangePassword` (bool), `createdAt`, `updatedAt`

> **Display-name rule, used everywhere:** `screenName != null ? screenName : email`.
> This is the whole of "show the email if they have no account, the screen name if they do" —
> because adding a member *always* creates the account (A6), a member with no screen name yet is
> exactly a member who has never signed in.

**Trip** — `id`, `slug`, `title` (required), `startDate` (required), `endDate` (required),
`ownerUserId`, `status` (`DRAFT`|`PUBLISHED`), `displayCurrency` (default `EUR`),
`exchangeRates` (`Map<String,BigDecimal>`), `publishedAt`, `publicSlug`, `createdAt`

**TripMember** — `userId`, `email`, `role` (`OWNER`|`MEMBER`), `invitedByUserId`, `invitedAt`,
`firstSignInAt` (nullable)

**Destination** — `id`, `tripId`, `name` (free text), `countryCode`, `countryName`,
`latitude`, `longitude` (all nullable), `geonameId` (nullable), `timezone`,
`startDate`, `endDate` (both **optional**), `notes`, `sortOrder`

**ChecklistItem** — `id`, `tripId`, `destinationId` (**nullable** — checklists exist with or
without a destination), `category` (`TRANSPORTATION`|`LODGING`|`ACTIVITIES`|`OTHERS`),
`description` (free text, editable), `note` (optional), `status` (`TODO`|`COMPLETED`),
`autoSeeded` (bool), `sortOrder`, `createdAt`, `completedAt`

**ItineraryItem** — `id`, `tripId`, `checklistItemId` (nullable), `category`, `description`,
`startAt`, `endAt` (`LocalDateTime`, optional), `cost` (`BigDecimal`, nullable), `currency`,
`budgetItemId` (nullable back-link), `sortOrder`

**BudgetItem** — `id`, `tripId`, `itineraryItemId` (nullable — manual expenses have none),
`category`, `description`, `amount`, `currency`, `date`, `createdAt`

**PublishRequest** — `id`, `tripId`, `requestedByUserId`, `status`
(`PENDING`|`APPROVED`|`REJECTED`), `requestedAt`, `decidedAt`, `decidedByUserId`

## A5. Permission rules (enforced in `TripAccessService`, one guard per request)

| Action | Who |
|---|---|
| Create trip | any signed-in user (becomes `OWNER`) |
| View / edit trip details | any member |
| Add member, remove member | **any member** |
| Remove the owner | **nobody** (409) |
| Remove yourself | allowed = "leave trip" |
| Add/edit/delete destination, checklist, itinerary, budget | **any member** |
| **Delete the whole trip** | **owner only** |
| **Publish / unpublish** | **owner only** |
| Request to publish | any non-owner member |
| Approve / reject a request | owner only |

## A6. Business rules

**Adding a member** (`POST /trips/{id}/members {email}`) — one transactional flow:
1. Find-or-create `User` by lowercased email.
2. If created: generate a readable default password (`Trip-7fk2-91xd`), BCrypt it, set
   `mustChangePassword=true`, `screenName=null`.
3. Add `TripMember{role: MEMBER}` (409 if already a member).
4. Send email — *invite + default password* for a new user, *"you've been added"* for an existing one.
5. Response uses the display-name rule, so the UI shows the email until they set a screen name.

**Seeding checklists on destination create** (`ChecklistSeeder`) — exactly three items:

| Category | Description |
|---|---|
| `TRANSPORTATION` | `Plan transportation to {name}` |
| `LODGING` | `Plan {n}N accommodation in {name}` — else `Plan accommodation in {name}` |
| `ACTIVITIES` | `Plan activities in {name}` |

`n = ChronoUnit.DAYS.between(startDate, endDate)`, emitted **only when both dates are present
and `end > start`**. Cusco 25-Oct → 31-Oct ⇒ `Plan 6N accommodation in Cusco`. ✅ matches spec.

Seeded items are ordinary free-text checklist items (`autoSeeded=true` is metadata only) —
any member can rewrite, recategorise or delete them.

**Destination rename does *not* rewrite checklist text** (it's user-owned free text after
seeding). **Deleting a destination nulls `destinationId` on its checklist items rather than
deleting them** — never destroy planning work as a side effect. Both stated as assumptions;
easy to flip.

**Checklist completion.** `COMPLETED` is only ever set by an explicit
`PATCH .../checklist/{id}/status`. Adding a plan does **not** auto-complete — that is what the
"Plan another" button is for. The `Set this Checklist to Complete` button is always available
(force-complete works with zero plans).

**Cost → budget cascade** (`BudgetSync`, one-way, predictable):

| Trigger | Effect |
|---|---|
| Itinerary saved with `cost != null` and no linked budget | create `BudgetItem` (same category, amount, currency, description; `date = startAt.toLocalDate()`) and store `budgetItemId` |
| Itinerary `cost`/`currency` changed, budget already linked | update **amount and currency only** |
| Itinerary `cost` cleared to null | delete the linked `BudgetItem` |
| Itinerary deleted | delete the linked `BudgetItem` |
| Budget `description`/`category`/`date` edited | **never** overwritten by later itinerary edits |
| Budget deleted directly | allowed; clears `itinerary.budgetItemId` |

**Plan pre-population** (`PlanTemplates`) — `GET /checklist/{id}/plan-template` returns a
suggested description + default times; every field stays editable:

| Category | Description | Default times |
|---|---|---|
| `TRANSPORTATION` | `Flight (XX 000) from {previous destination} to {name}` — or `Transport to {name}` if it's the first | destination `startDate` 00:00 |
| `LODGING` | `Hotel in {name} — check-in {d MMM}, check-out {d MMM}` | `startDate` 15:00 → `endDate` 11:00 |
| `ACTIVITIES` | `Activity in {name}` | `startDate` 09:00 |
| `OTHERS` | the checklist description verbatim | none |

"Previous destination" = the destination immediately before this one by `startDate`, then `sortOrder`.

**Budget totals.** `Trip.exchangeRates` is a hand-maintained map keyed by currency code with the
rate *to* `displayCurrency` (identical to `_data/travels/budget/*.yml`). No FX API. A budget item
in a currency with no rate is summed separately and flagged in the response so the UI can warn.

## A7. Auth

- **JWT HS256 in an httpOnly cookie** (`tp_session`, `SameSite=Lax`, 12h TTL) — no token in JS.
- **CSRF double-submit**: `XSRF-TOKEN` cookie echoed as `X-XSRF-TOKEN` on non-GET.
  `GET /api/v1/auth/csrf` primes it. Same contract as `travel-app/travel-web/js/api.js`.
- Signing secret from `app.security.jwt-secret`; if blank, generate once and persist to
  `data/secret.yml` so sessions survive a restart.
- **No self-signup.** Accounts exist only via `POST /trips/{id}/members` — plus a
  `app.bootstrap.owner-email` / `owner-password` config pair that seeds the very first user at
  startup (otherwise nobody can ever log in).
- **`mustChangePassword` gate**: a `HandlerInterceptor` rejects every endpoint except
  `/auth/me`, `/auth/logout`, `/account/password` with **`409 password_change_required`** while
  the flag is set. `/auth/me` returns `mustChangePassword: true` so the FE can redirect.
  `POST /account/password` clears the flag and accepts an optional `screenName` in the same call.

## A8. REST API — `/api/v1`

```
POST   /auth/login                 {email,password}
POST   /auth/logout
GET    /auth/me                    -> {id,email,screenName,displayName,mustChangePassword}
GET    /auth/csrf
PATCH  /account/profile            {screenName}
POST   /account/password           {currentPassword,newPassword,screenName?}

GET    /trips                                       # trips I own or am a member of
POST   /trips                      {title,startDate,endDate}
GET    /trips/{tripId}                              # full bundle: trip+members+destinations
                                                    #  +checklist+itinerary+budget+publish
PATCH  /trips/{tripId}             {title?,startDate?,endDate?,displayCurrency?,exchangeRates?}
DELETE /trips/{tripId}                              # owner only

GET    /trips/{tripId}/members
POST   /trips/{tripId}/members     {email}
DELETE /trips/{tripId}/members/{userId}

GET    /geocode?q=cusco                             # countries.dev, merged+deduped
GET    /trips/{tripId}/destinations
POST   /trips/{tripId}/destinations {name,startDate?,endDate?,latitude?,longitude?,
                                     countryCode?,geonameId?}   # -> seeds 3 checklist items
PATCH  /trips/{tripId}/destinations/{id}
DELETE /trips/{tripId}/destinations/{id}

GET    /trips/{tripId}/checklist
POST   /trips/{tripId}/checklist   {category,description,note?,destinationId?}
PATCH  /trips/{tripId}/checklist/{id}          {description?,note?,category?,destinationId?}
PATCH  /trips/{tripId}/checklist/{id}/status   {status: TODO|COMPLETED}
DELETE /trips/{tripId}/checklist/{id}
GET    /trips/{tripId}/checklist/{id}/plan-template

GET    /trips/{tripId}/itinerary
POST   /trips/{tripId}/itinerary   {checklistItemId?,category,description,startAt?,endAt?,
                                    cost?,currency?}
PATCH  /trips/{tripId}/itinerary/{id}
DELETE /trips/{tripId}/itinerary/{id}

GET    /trips/{tripId}/budget                       # items + byCategory + total + unconverted[]
POST   /trips/{tripId}/budget      {description,category,amount,currency,date?}
PATCH  /trips/{tripId}/budget/{id}
DELETE /trips/{tripId}/budget/{id}

POST   /trips/{tripId}/publish                      # owner -> renders, status=PUBLISHED
DELETE /trips/{tripId}/publish                      # owner -> unpublish, deletes dir
POST   /trips/{tripId}/publish-requests             # non-owner member
GET    /trips/{tripId}/publish-requests
POST   /trips/{tripId}/publish-requests/{id}/approve # owner -> publish + email requester
POST   /trips/{tripId}/publish-requests/{id}/reject

GET    /p/{publicSlug}                              # permitAll, static published HTML
```

Errors are RFC-7807 `ProblemDetail` via `GlobalExceptionHandler`, with a `fieldErrors` map on
validation failures — the shape `api.js` already parses.

## A9. Publish

`StaticSiteRenderer` writes a **self-contained** `data/published/<slug>/index.html`:
inlined CSS (the trip's chosen theme), inlined `<script>window.TRIP = {...}</script>`, zero
network calls, plus a sibling `trip.json` for reuse. Structure follows `_layouts/travel.html`:
trip header with dates and country flags, the panel toggle
(About / Itinerary / Checklist / Budget), destinations, itinerary grouped by day, checklist
todo-first, budget by category with totals.

`PublicPageController` serves it at `/p/{slug}` with `permitAll`. Because it's fully
self-contained, `data/published/` is a drop-in upload for S3 / Netlify / GitHub Pages —
that is the "CDN" step, and no code change is needed to do it.

## A10. Email

`EmailSender` interface, three implementations chosen by `app.mail.mode`:

- `log` — logs subject + body
- `file` (**default**) — writes `data/outbox/<ts>-<to>.eml`, so you can actually read a new
  member's default password in local dev
- `smtp` — `@ConditionalOnProperty(name="app.mail.mode", havingValue="smtp")`, JavaMailSender

Templates: member invite (with default password), member added (existing account),
publish requested (→ owner), publish approved / rejected (→ requester).

## A11. Config reference

```yaml
feature-enable-database: false
app:
  storage:  { root: ./data }
  publish:  { dir: ./data/published, public-base-url: http://localhost:8080/p }
  mail:     { mode: file, from: no-reply@travel-planner.local }
  security: { jwt-secret: "", session-ttl: 12h, cookie-secure: false }
  cors:     { allowed-origins: http://localhost:3000 }
  geocoding:{ base-url: https://countries.dev, timeout: 5s, city-limit: 8, place-limit: 20 }
  bootstrap:{ owner-email: "", owner-password: "" }
```

## A12. Backend build order

1. Gradle skeleton, `AppProperties`, `FeatureFlags`, `GlobalExceptionHandler`, health check
2. `storage/` — `YamlStore`, atomic write, `TripLocks` + unit tests
3. `identity/` — User, BCrypt, JWT cookie filter, CSRF, `mustChangePassword` interceptor, bootstrap owner
4. `trips/` — CRUD, `TripAccessService`, members (find-or-create + email), owner-only delete
5. `geocoding/` — countries.dev client (404-as-empty, `/cities`+`/places` merge, `/alpha` cache)
6. `destinations/` — CRUD + `ChecklistSeeder` (nights math)
7. `checklist/` — CRUD, status, `plan-template`
8. `itinerary/` + `budget/` — CRUD and the `BudgetSync` cascade
9. `publish/` — requests, approval, `StaticSiteRenderer`, `/p/{slug}`
10. `notification/` — swap the log sender for the file sender, wire all templates
11. Seed script importing the LATAM trip from `_data/travels/*` as demo data

---

# PART B — Frontend (`travel-planner/planner-web`)

## B1. Stack

Static pages, **no bundler**. Alpine.js **vendored locally** (`vendor/alpine.min.js`, ~15KB, no
CDN dependency) for declarative binding, plus vanilla ES modules for the API layer — the same
shape as `travel-app/travel-web`, which is already familiar. Dart Sass (1.100.0 already
installed globally) is the only build step. `serve.sh` runs `python3 -m http.server 3000`.

```
planner-web/
  package.json            # devDep: sass — scripts: css, css:watch, dev
  serve.sh
  index.html  login.html  change-password.html  account.html
  trips.html  trip.html   404.html
  partials/header.html  partials/theme-selector.html   # injected client-side
  js/  config.js api.js format.js session.js theme-selector.js
       nav.js password-toggle.js toast.js
       pages/{login,change-password,account,trips}.js
       pages/trip/{index,overview,members,destinations,checklist,itinerary,budget,publish}.js
  scss/ …                 # B2
  assets/css/             # generated — gitignored
  vendor/alpine.min.js
```

`js/api.js` is a direct port of `travel-app/travel-web/js/api.js` (cookie credentials, CSRF echo,
`ApiError` with `fieldErrors`), extended with the endpoints in A8 and one extra rule: a
**409 `password_change_required` redirects to `change-password.html`**.

## B2. SCSS architecture

Modelled on `josephinealinea.github.io` (one compiled stylesheet per theme, toggled by
`<link disabled>`), but **fixing its main weakness**: those four theme files are ~1,000 lines
each and duplicate all structural CSS. Here, structure lives once in `_core`, and a theme is a
**token file** — colours, fonts, radii, shadows — plus a short block of theme-specific flourishes.
Uses modern `@use`/`@forward` (Dart Sass 1.100 deprecates `@import`).

```
scss/
  abstracts/   _tokens.scss _mixins.scss _functions.scss _breakpoints.scss
  base/        _reset.scss _typography.scss _forms.scss _utilities.scss
  layout/      _header.scss _footer.scss _page.scss _tabs.scss _grid.scss
  components/  _button.scss _card.scss _table.scss _modal.scss _drawer.scss
               _badge.scss _chip.scss _toast.scss _empty-state.scss
               _theme-selector.scss _password-field.scss _autocomplete.scss
               _checklist-item.scss _timeline.scss _budget-chart.scss
  pages/       _login.scss _trips.scss _trip.scss _public-trip.scss
  themes/      _minima.scss _retro-game.scss _y2k.scss _manila.scss
  _core.scss                       # @use everything above (structure only, tokens by var)
  minima.scss  retro-game.scss  y2k.scss  manila.scss     # entry points
  public-page.scss                 # standalone, inlined into published HTML
```

Every colour/font in `_core` reads a CSS custom property (`--tp-bg`, `--tp-surface`, `--tp-text`,
`--tp-accent`, `--tp-border`, `--tp-danger`, `--tp-font-body`, `--tp-font-display`, `--tp-radius`,
`--tp-shadow`). A theme file defines those under `body.theme-<name>`. Adding a fifth theme = one
token file + one entry point + one `THEME_REGISTRY` line + one `<link>`.

```jsonc
// package.json
"scripts": {
  "css": "sass scss/minima.scss:assets/css/minima.css scss/retro-game.scss:assets/css/retro-game.css scss/y2k.scss:assets/css/y2k.css scss/manila.scss:assets/css/manila.css scss/public-page.scss:assets/css/public-page.css --no-source-map",
  "css:watch": "npm run css -- --watch"
}
```

## B3. Theme selector

Port `assets/js/theme-selector.js` verbatim in mechanism (drop its debug `console.log`s and the
site-specific nav/skill-bar code). Each page head carries:

```html
<link rel="stylesheet" href="assets/css/minima.css"      id="minima-css">
<link rel="stylesheet" href="assets/css/retro-game.css"  id="retro-game-css" disabled>
<link rel="stylesheet" href="assets/css/y2k.css"         id="y2k-css"        disabled>
<link rel="stylesheet" href="assets/css/manila.css"      id="manila-css"     disabled>
```

`THEME_REGISTRY` maps theme → `{stylesheetId, labelFull, labelShort}`; selection persists in
`localStorage.selectedTheme`, sets `body.theme-<name>`, enables one stylesheet and disables the
rest, and uses short labels below 768px. The selector sits in the shared header on **every**
page including login. The trip's publish action records the current theme so the published page
is rendered in it.

## B4. Shared chrome

- **Header** — app name → `trips.html`; when signed in: "My Trips", display name, "Account",
  "Sign out"; theme selector on the right.
- **`password-toggle.js`** — the 👁 / 🙈 eye button. Attaches to any
  `<div class="password-field">`, toggles `type` between `password`/`text`, updates
  `aria-label` and `aria-pressed`, never loses focus or caret. **Used on every password input
  in the app** (login, change-password, account).
- **`toast.js`** — success/error toasts (e.g. *"Cusco added — 3 checklist items created"*).
- **Confirm modal** — reused for every destructive action. Trip delete requires typing the
  trip title.

## B5. Screens

### 1. `login.html` — Sign in
- Fields: **Email**; **Password** *(with 👁 show/hide eye icon)*
- Buttons: **Sign in** (primary, full width, disabled + "Signing in…" while busy)
- Links: "Forgot your password? Ask a trip member to re-invite you." (static — no reset flow)
- Error banner for bad credentials. Theme selector in header.
- On success: `mustChangePassword` → `change-password.html`, else `trips.html`.
- If already signed in, redirects straight to `trips.html`.

### 2. `change-password.html` — First sign-in / forced change
Headline *"Set your password"*; subtitle *"You signed in with a temporary password. Choose a new one and tell us what to call you."*
- Fields: **Current password** *(👁)*; **New password** *(👁,* min 8, live strength hint*)*;
  **Confirm new password** *(👁,* live match hint*)*; **Screen name** (shown when unset, with
  helper *"This is what other trip members will see instead of your email."*)
- Buttons: **Save and continue** (primary) · **Sign out** (ghost)
- Cannot be skipped while `mustChangePassword` is set — no header nav links rendered.

### 3. `account.html` — Account
- **Profile** card: **Screen name** field · **Save screen name** button · read-only email
- **Password** card: Current *(👁)*, New *(👁)*, Confirm *(👁)* · **Change password** button
- **Appearance** card: theme radio list (same registry as the selector)
- **Sign out** button

### 4. `trips.html` — My Trips *(landing page after sign-in)*
- **+ New Trip** button (top right) → modal: **Title*** , **Start date*** , **End date***
  (end ≥ start), buttons **Create trip** · **Cancel**
- Trip cards: title, date range, `Owner`/`Member` badge, `Draft`/`Published` badge, member
  avatars, checklist progress bar (`7 / 19 done`)
- Per card: **Open** · **Delete** *(owner only — confirm by typing the title)* ·
  **View public page** *(published only)*
- Empty state: *"No trips yet"* + **Create your first trip**

### 5. `trip.html?id={id}` — Trip workspace
One page, one bundle fetch (`GET /trips/{id}`), hash-routed tabs (`#members`, `#checklist`, …) —
each tab is its own Alpine component in `js/pages/trip/`.

**Sticky header:** trip title, date range, `Draft`/`Published` badge, owner name, member avatars.
Buttons: **Edit trip** (modal: title/start/end/display currency/exchange rates) ·
**Delete trip** *(owner only)*.
**Tabs:** `Overview · Members · Destinations · Checklist · Itinerary · Budget · Publish`

**Overview** — stat tiles (destinations, checklist done/total, itinerary entries, budget total in
display currency, days to departure); "Up next" list of the nearest itinerary items; a
*"Needs planning"* list of TODO checklist items with no plan, each with a **Plan** shortcut.

**Members** — table: *Display name* (screen name, or email in muted italic with a
`Not signed in yet` chip) · *Email* · *Role* · *Added*.
Buttons: **Add member** (email input + **Send invite**, inline validation, 409 → "already a
member") · per-row **Remove** *(disabled with a tooltip on the owner row)* · **Leave trip** on
your own row.

**Destinations** — table: *Name · Country (flag) · Lat / Lon · Start · End · Nights · Checklists*.
- **+ Add destination** → form with a **name autocomplete** (debounced 250ms →
  `GET /geocode?q=`, dropdown of `name — region, Country · pop 428k`; picking one fills country
  and lat/lon). **Free text is always accepted** — no match required. Lat and Lon are plain
  editable number inputs, filled by the pick and overridable right there.
- Fields: **Name*** · **Country** · **Latitude** · **Longitude** · **Start date** (optional) ·
  **End date** (optional) · **Notes**. Buttons: **Add destination** · **Cancel**.
- On success: toast *"Cusco added — 3 checklist items created"*.
- Per row: **Edit** (same form, incl. lat/lon) · **Delete** (confirm warns checklist items are
  kept and unlinked) · drag handle to reorder.

**Checklist** — the core screen.
- Toolbar: status filter `All / TODO / Completed` · category filter chips
  `Transportation · Lodging · Activities · Others` · group-by `Destination / Category / None` ·
  **+ Add checklist item**
- Rows (TODO first, matching `_layouts/travel.html`): status icon `▫️`/`✔️` · category chip ·
  description · note snippet · destination chip · `2 plans` badge · inline **✓ Complete** quick-action
- **+ Add checklist item** form: **Category*** (select) · **Description*** (free text) ·
  **Note** · **Destination** (optional select) → **Add** · **Cancel**
- Clicking a row opens the **Checklist detail drawer**:
  - Editable **Description**, **Note**, **Category**, **Destination** → **Save changes**
  - **Plans** list — each showing date/time range, description, cost, with **Edit** · **Delete**
  - **Plan** button → **Plan form** (pre-filled from `GET .../plan-template`):
    **Start date & time** · **End date & time** · **Description** (e.g.
    *"Delta flight (DL 6071) from LAX to LIM"* — fully editable) · **Cost** (optional) ·
    **Currency** (select, defaults to the destination country's currency)
    → **Save plan** · **Cancel**. Helper under Cost: *"Added cost creates a matching budget."*
  - Footer buttons: **Set this Checklist to Complete** *(always enabled; becomes
    **Reopen** when completed)* · **Plan another** · **Delete checklist item**

**Itinerary** — timeline grouped by date, each entry showing time range, category icon, description,
cost, and the checklist it came from. **+ Add itinerary item** (standalone, no checklist required),
per-entry **Edit** · **Delete**. Filter chips by category; toggle *"Only show items with a cost"*.

**Budget** — table: *Description · Category · Amount · Currency · Converted · Date · Source*
(`From plan: …` link, or `Manual`).
- Summary: per-category totals with the colours from `_data/travels/budget_categories.yml`,
  a category donut, and a grand total in the display currency; a warning row lists any currency
  missing an exchange rate.
- Buttons: **+ Add expense** (Description*, Category*, Amount*, Currency*, Date) · per-row
  **Edit** · **Delete** · **Display currency** select · **Edit exchange rates** modal.

**Publish**
- *Draft, owner:* status card + **Publish trip** (confirm: *"Anyone with the link will be able to
  see this trip."*)
- *Draft, member:* **Request to Publish** → after sending, *"Requested — waiting for {owner}"* with
  **Cancel request**
- *Owner with pending requests:* list of requester + timestamp, each with **Approve & publish** ·
  **Reject**
- *Published:* public URL with **Copy link** · **Open public page** · **Re-publish (update)** ·
  **Unpublish** *(owner only)*; "last published" timestamp; theme picker for the published page.

### 6. `/p/{slug}` — Public page (BE-rendered, no auth)
Read-only, self-contained. Mirrors the reference layout: trip title + dates + country flags,
panel toggle (About / Itinerary / Checklist / Budget), destinations with map links, itinerary by
day, checklist todo-first, budget by category with totals. Styled from `scss/public-page.scss`
inlined at render time.

### 7. `index.html` / `404.html`
`index.html` is a redirect shim: `/auth/me` → `trips.html`, else `login.html`.

## B6. Frontend build order

1. `package.json`, `serve.sh`, SCSS skeleton, `minima` theme, `_core` structure
2. Shared: `api.js`, `session.js`, header partial, `theme-selector.js`, `password-toggle.js`, `toast.js`
3. `login.html` → `change-password.html` → `account.html` (the full first-sign-in path)
4. `trips.html` + create/delete
5. `trip.html` shell + tabs + Overview + Members
6. Destinations (incl. the geocode autocomplete with editable lat/lon)
7. Checklist list + detail drawer + Plan form *(the highest-value screen — budget time here)*
8. Itinerary + Budget
9. Publish tab + publish-request flow
10. The three remaining themes (`retro-game`, `y2k`, `manila`) as token files
11. Responsive pass + a11y pass (focus traps in modal/drawer, labelled eye toggles, keyboard nav)

---

## Verification

**Backend unit/slice tests**
- `NightsTest` — 25-Oct→31-Oct = 6; equal dates = null; one date only = null; end < start = null
- `ChecklistSeederTest` — asserts exactly `Plan transportation to Cusco`,
  `Plan 6N accommodation in Cusco`, `Plan activities in Cusco`
- `YamlStoreTest` — round-trip incl. `LocalDate`/`LocalDateTime`, atomic replace, concurrent
  read-modify-write under `TripLocks`
- `BudgetSyncTest` — the six cascade rows in A6
- `GeocodingClientTest` — MockRestServiceServer: 404 → empty list; `/places` filtered to
  `featureClass=="P"`; merge dedupes on `geonameId`
- `TripAccessServiceTest` — every row of the A5 permission table, including owner-removal 409
- `@SpringBootTest` + MockMvc: full happy path — bootstrap owner → login → create trip →
  add member → destination → seeded checklist → plan with cost → budget appears → publish

**Manual end-to-end** (`./gradlew bootRun` + `npm run css && ./serve.sh`, using the LATAM seed):
1. Sign in as the bootstrap owner at `http://localhost:3000/login.html` — click the eye icon,
   confirm the password reveals and hides.
2. Create *"LATAM Trip 2026"*, 2026-10-24 → 2026-11-08.
3. Members → add a new email → confirm the row shows the **email**. Read the default password
   from `data/outbox/*.eml`. Sign in as that member in a private window → forced onto
   change-password → set password + screen name → confirm the owner's Members tab now shows the
   **screen name**.
4. Destinations → type `cusco` → pick the `/cities` hit (lat -13.53188 / lon -71.96701) →
   set 25-Oct → 31-Oct → **verify `Plan 6N accommodation in Cusco` appears in Checklist**.
5. Type `ollantaytambo` → confirm the `/places` fallback surfaces it (it is *absent* from `/cities`).
6. Type `aguas calientes` → confirm no Peru match, then enter lat -13.1547 / lon -72.5253 by
   hand and save successfully. **This is the manual-override path.**
7. Checklist → open *Plan transportation to Cusco* → **Plan** → confirm the description is
   pre-filled → set cost 246.22 USD → save → **Budget tab shows a matching transport row**.
8. **Plan another** → add a second plan → confirm the item is still TODO → click
   **Set this Checklist to Complete** → it moves to Completed.
9. Edit the plan's cost → budget amount follows. Clear the cost → the budget row disappears.
10. Sign in as the member → **Request to Publish** → owner sees it → **Approve & publish** →
    open `/p/<slug>` **in a private window** and confirm it renders signed-out.
11. Switch themes on every page; reload; confirm the choice sticks (`localStorage`) and that
    `data/published/<slug>/index.html` opens correctly **from the filesystem** (proving it is
    self-contained and CDN-ready).
12. Restart the API → confirm all data reloads from `data/` and sessions survive.

## Assumptions to flag (easy to flip)

1. Adding a member always creates the account — that's what makes "email vs screen name" work.
2. Renaming a destination does not rewrite already-seeded checklist text.
3. Deleting a destination unlinks its checklist items rather than deleting them.
4. Adding a plan never auto-completes a checklist item; only the explicit button does.
5. Deleting an itinerary item deletes the budget row it created.
6. No self-signup and no password-reset email — a bootstrap owner in config, then invites.
7. Exchange rates are hand-maintained per trip (as in `_data/travels/budget/*.yml`); no FX API.
8. Single API instance — the YAML store is file-locked, not cluster-safe.
