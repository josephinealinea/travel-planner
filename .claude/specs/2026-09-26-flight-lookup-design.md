# Flight lookup and live status: design spec

Status: DRAFT for review. No open questions. No code written. After
approval, the next document is an implementation plan in `.claude/plans/`.

## Goal

A member types a flight number on a transport plan and, with one click, gets the
flight's times filled in and its airports saved with the entry. On a published page, a reader can refresh
each flight to see its latest status, gate and delay. Both work when the number
on the ticket is a codeshare (KL2842 flown as airBaltic BT857) without the
member ever having to know it is one.

## Scope

In:
- A `flight` document (number, operating number, airport snapshot) on itinerary
  entries of the `TRANSPORTATION` category, from both forms
  (the checklist Plan form and the Itinerary tab's entry form).
- A lookup button next to the field that fills the blank fields.
- A `flights/` module in the API: two clients, a cache, a member-only lookup
  endpoint, a public refresh endpoint and a nightly job. It uses a shared
  `api_usage` counter (see Quota counter) that is not flight-specific.
- A refresh icon per flight on the published page.

Out (not v1):
- Live status inside the planner (the Itinerary tab shows the saved snapshot).
- Route-and-time matching when a number finds nothing.
- Air quality, flight plans, aircraft position and images.
- Any paid plan of either service.

## What the probes established

Live calls, 26 Sep 2026. These are facts, not assumptions.

- **AeroDataBox** (`/flights/number/{n}/{date}`, `dateLocalRole=Departure`)
  returns an array. Per flight: departure and arrival blocks with airport
  (iata, icao, name, municipality, country, timezone, location), scheduled,
  revised and predicted times as `utc` and `local` with offset, terminal, check-in
  desk, `status`, `codeshareStatus`, `aircraft.model`, `airline`,
  `lastUpdatedUtc`. Schedules exist weeks ahead (BA117 for 24 Oct returned). **A
  number it does not know returns an empty body, not an error.**
- **KL2842 is not found by AeroDataBox** under any of three query shapes.
  Tallinn's board that day lists BT 857 (07:30 to AMS) and no KLM number.
  `codeshareStatus` is `Unknown` on nearly every Tallinn flight.
- **AviationStack** (`/v1/flights?flight_iata=KL2842`, no date) returns the flight
  with `flight.codeshared` naming the operating flight (`bt857`), plus gate,
  baggage belt, delay in minutes and actual times. It returned yesterday's and
  today's rows only.
- `flight_date` on AviationStack answers **403 `function_access_restricted`** on
  the free plan, so dated or far-future lookups are impossible there.
- **AviationStack labels local time as UTC**: Tallinn's 07:30 departure comes back
  as `07:30+00:00`, while AeroDataBox has `07:30+03:00`. Its scheduled and
  estimated times must not be used for filling the form.
- The free plan is HTTP only, so the key travels in plain text.
- AviationStack and AeroDataBox do not need each other's key, and the design
  needs neither to be up for a save to work.

## Roles of the two services

| Service | Used for | Never used for |
|---|---|---|
| AeroDataBox | Schedule, airports, times with correct offsets, terminal, status, aircraft. The source of truth. | Codeshare resolution (it cannot). |
| AviationStack | (1) Find the operating flight: only after AeroDataBox has just answered empty, so at most once per number per negative-TTL window, never again once a pairing is stored. (2) From 2 h before departure until landed: gate, baggage belt, delay, actual times. | Scheduled or estimated times, airports, anything the form fills. |

## Behaviour

### Entry form (both forms)

- The flight number field exists **only when the category is transport** (the
  `TRANSPORTATION` category; its data key is `transport`), and is optional, because transport also covers trains and buses. Its label says so.
- A lookup icon sits beside it. It is enabled only when there is a number and a
  start date. Without a date it explains that it needs one. The Plan form's date
  is pre-filled from the template, so the icon is often enabled at open.
- **One click does the lookup and the filling.** There is no preview step. A hit
  fills the blank fields straight away and leaves a note under the field ("Filled
  from KL2842, operated by BT857 · TLL 07:30 to AMS 09:00"), so the member can see
  what happened and edit or clear anything.
- The lookup **never runs on save or on typing**, so it cannot block or fail a save.
- It fills **only fields that are blank**: start time, end date, end time and the
  flight snapshot. It never overwrites something typed. If a start time is already
  there, nothing time-related changes.
- The endpoint answers one of three statuses: `found`, `notFound` (the service
  answered and has no such flight) or `unavailable` (no key, cap reached, timeout or
  an error). A miss says "No live data found, fill in the times yourself"; an
  unavailable answer says "Flight lookup is unavailable right now, fill in the
  times yourself". The form stays fully usable in both.
- The lookup fills the **form fields**, and the existing save logic does the rest:
  a filled start time sends `allDay: false`, and a blank one keeps the all-day
  rule. The server-side rule about all-day entries does not change.
- Times are filled as **wall-clock time at the airport**, the same convention the
  timestamps already use (`timestamp` without time zone). AeroDataBox's `local`
  value is used, never `utc`.
- **Editing the number or the start date after a lookup clears the note and the
  pending snapshot**, so a snapshot can never be saved against a different flight.
  Times already filled stay, since they are ordinary field values by then.
- The snapshot travels with the save (see Data). A form that never did a lookup
  sends no flight document, so opening and saving never changes one.

### Lookup order (`FlightLookup`)

1. Normalise the number (strip spaces, upper case).
2. Ask AeroDataBox for that number on that date. If it returns a flight whose
   departure local date matches, done.
3. On a miss, ask AviationStack for the number (no date). Take the first row with
   a non-null `codeshared`, and its `flight_iata` is the operating number. If the
   mapping is already known, skip this call.
4. Ask AeroDataBox for the operating number on the same date.
5. Store the mapping (see Data) and return the result plus the operating number.
6. If the services answered and nothing was found, return `notFound` and store the
   miss briefly. **Only a genuine empty answer is cached as a miss.** A timeout, an
   error or a cap-reached answer is `unavailable` and is never stored as
   "not found", or an outage would poison the cache for `negative-ttl`.
7. Each successful AeroDataBox answer is **written to `flight_records`**, so the
   lookup that filled the form also warms the cache for the published page.

Best effort by design. AviationStack sees only flights around today, so a weekly
flight not operating this week may not resolve. That is accepted.

### Published page

- Nothing is fetched on load. Each flight card shows what was published: the
  number as booked, the operating number if known, scheduled times and the
  snapshot airports and terminal.
- A small refresh icon on each flight card calls the public endpoint. The reply
  is drawn onto the card and **cached in the browser** for the TTL the server
  sent, so reopening the page does not spend a call.
- Failure or no data leaves the published card as it was, with no error text
  beyond a quiet "not available".
- Each card shows a small "Updated HH:MM": the time **we last fetched**, not the
  time the reader clicked, so a cached answer keeps its older time. Schedule
  fields (AeroDataBox) and gate, baggage and delay (AviationStack) each carry
  their own time, because they are fetched separately. Shown in the reader's
  local time, with the date added when it is not today. The server sends
  `fetchedAt` for each group, and the browser cache stores it with the reply.

## Data

### On the itinerary entry (permanent, member-editable)

`ItineraryItem.flight`, one document (a `jsonb` column `flight` in Postgres, a
nested mapping in YAML; the same document pattern as
`users.published_page` and `weather.details`):

- `number`: as booked (KL2842).
- `operatingNumber`: BT857, absent when none. Filled by the lookup and editable.
- `from` / `to`: airport `iata`, `icao`, `name`, `city`, `countryCode`,
  `timezone` and `lat` / `lon`. All of it comes free in AeroDataBox's flight
  payload. Nothing in v1 uses the coordinates or the country; they are kept
  because adding them later would mean refetching every saved flight (weather at
  an airport, a map link and country chips are the obvious uses). Airport data
  comes from AeroDataBox only: AviationStack has no coordinates and its names are
  worse ("Ulemiste" for Tallinn).
- `terminalFrom` / `terminalTo`.

A document holding only `number` is valid: a member who types a number and never
uses the lookup still gets it on the plan card and the published page, just with no
airports.

Only facts that do not change day to day. Gate, baggage and delay are **not**
copied here: they are stale within hours and this is member data, not a cache.

Request semantics follow the payer pattern: absent leaves the field alone, an
empty number clears the whole document, a value sets it.

A stay's later days do not carry a flight (only the plan's own row does), the same
rule as cost.

**Transport only, enforced on the server.** A `flight` on an entry whose category is
not transport is refused with a 400 (`ApiException.badRequest`, a message key), so a
hand-made request cannot create one. If an entry's category is later changed away
from transport, its flight document is cleared.

### Flight cache (install-wide, not per trip)

`FlightRecord`, keyed by operating flight number and departure local date:

- `schedule`: AeroDataBox's fields (times, status, terminal, aircraft, airports)
  with `scheduleFetchedAt`.
- `live`: AviationStack's extras (gate, baggage belt, delay, actual times, status)
  with `liveFetchedAt`.
- One `jsonb` document like weather's `details`, so a new field is a line in the
  catalogue and a chip, not a migration. Absent means unknown and 0 is an answer
  (a real 0 minute delay is shown), so every frontend test is `!= null`.
- A miss is stored as a record with no data and a short `negative-ttl`.

### Codeshare map (install-wide)

`CodeshareMapping`: booked number to operating number, permanent, no TTL. The
pairing is a fact about the schedule and rarely changes. An entry that was
edited to a different number simply looks up that number.

### Quota counter

`ApiUsageService` and `ApiUsageRepository` (table `api_usage`), deliberately **not flight-specific** so another API can be counted
later by adding a service key and no schema change. It lives in its own small
package (`usage/`) beside `rates/` and `weather/`, and `flights/` calls it. Keyed by
service and calendar month in UTC (`aerodatabox` + `2026-10`), holding a count.
The service name is just a string key, and each service's monthly limit and cap
stay in that service's own settings (`FlightProperties` for these two). Only
AeroDataBox and AviationStack are counted in v1; Open-Meteo, the exchange rates
and countries.dev are left alone. **A new month is a new key, so nothing has to reset.**
Increments happen under a lock (`TripLocks`-style in YAML, an upsert with
`count = count + 1` in Postgres). Old months stay as usage history.

### Storage shape

Both stores, one design, in the same style as the weather details work (`V11`).

**Entry field.** `ItineraryItem.flight`, absent when there is none (`NON_NULL`).

YAML, nested inside the entry in `travels/itinerary/<slug>.yml`:

```yaml
- id: 6f1c2e0a-3b7d-4c55-9d2e-1a8b5c7e9f01
  tripId: c0f1505a-98ad-46b7-a2cd-865c85bf3fe3
  category: TRANSPORTATION
  description: KLM flight from Tallinn to Amsterdam
  startAt: 2026-10-24T07:30:00
  endAt: 2026-10-24T09:00:00
  flight:
    number: KL2842
    operatingNumber: BT857
    from: {iata: TLL, icao: EETN, name: Tallinn Lennart Meri, city: Tallinn,
           countryCode: EE, timezone: Europe/Tallinn, lat: 59.4133, lon: 24.8328}
    to:   {iata: AMS, icao: EHAM, name: Amsterdam Schiphol, city: Amsterdam,
           countryCode: NL, timezone: Europe/Amsterdam, lat: 52.3086, lon: 4.7639}
    terminalFrom: null
    terminalTo: null
```

Postgres, migration `V13__flight_lookup.sql`, schema only (no rows, no data
moves, so `MigrationsAreSchemaOnlyTest` stays green):

```sql
ALTER TABLE itinerary_items ADD COLUMN flight jsonb;   -- nullable, no default
```

Nullable with no default, so an entry with no flight is the same as before and old
rows need nothing. Bound as text and cast in the statement (`:flight::jsonb`),
through `TripScopedJdbcRepository`'s `jsonColumns` set, like
`users.published_page`. The field is added to the `EveryField` fixture so the
round-trip test fails if it is left unmapped.

**Three install-wide tables** (`flight_records`, `codeshare_mappings` and the shared `api_usage`). None has `trip_id` or a foreign key to `trips`, so a
trip delete does not touch them and
`everyTableReferencingTripsIsCovered` is unaffected.

```sql
CREATE TABLE flight_records (
    flight_number       text        NOT NULL,   -- operating number, upper case, no spaces
    departure_date      date        NOT NULL,   -- local date at the origin airport
    schedule            jsonb,                  -- AeroDataBox fields
    live                jsonb,                  -- AviationStack extras
    schedule_fetched_at timestamptz,
    live_fetched_at     timestamptz,
    not_found           boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (flight_number, departure_date)
);

CREATE TABLE codeshare_mappings (
    booked_number    text        PRIMARY KEY,   -- KL2842
    operating_number text        NOT NULL,      -- BT857
    resolved_at      timestamptz NOT NULL
);

CREATE TABLE api_usage (
    service text    NOT NULL,                   -- 'aerodatabox', 'aviationstack', later others
    month   text    NOT NULL,                   -- '2026-10', UTC calendar month
    calls   integer NOT NULL DEFAULT 0,
    PRIMARY KEY (service, month)
);
```

- **Instants are `timestamptz`, dates are `date`.** These are real moments (when
  we fetched), unlike an entry's wall-clock `start_at`, which stays `timestamp`
  without zone.
- **Enums and states are plain columns**, no `CHECK`, like the other tables.
- **`not_found` is a stored negative result**, expiring by `negative-ttl` measured
  from `schedule_fetched_at`.
- **The counter is one upsert**: `INSERT … ON CONFLICT (service, month) DO UPDATE
  SET calls = api_usage.calls + 1`, so two concurrent calls cannot lose a count.
  A new month is a new row; nothing resets. The check and the increment are **one
  operation**: `ApiUsageService.tryAcquire(service, cap)` (over `ApiUsageRepository.tryAcquire(service, month, cap)`) returns whether a call may go
  out, as a conditional upsert (`… DO UPDATE SET calls = api_usage.calls + 1 WHERE
  api_usage.calls < :cap`), so the cap cannot be overshot by a race. The cap comes
  from the caller's own settings, and the call is **counted when attempted**, even
  if it then fails, since the provider counts attempts.
- **Explicit NULLs.** The JDBC mappers write the default themselves for
  `not_found` (false), per the "explicit NULL does not fall back to a default"
  trap.

**YAML equivalents.** Install-wide, like `data/rates.yml`, written through
`YamlStore` (temp file, then move) under one lock, so single-instance only, the
same as every YAML store. Each file is a plain list; a row's key is the same
column set as its table, so the two stores stay one-to-one. Field names are the
camel-case of the columns, `NON_NULL`, ISO dates and instants.

`data/flights/records.yml` (table `flight_records`, key `flightNumber` +
`departureDate`). Values below are from real responses:

```yaml
- flightNumber: BT857
  departureDate: 2026-10-24
  notFound: false
  scheduleFetchedAt: 2026-10-22T01:00:04.211Z
  liveFetchedAt: 2026-10-24T05:12:40.870Z
  schedule:                       # AeroDataBox, keyed by the catalogue
    status: Expected
    aircraftModel: Airbus A220-300
    departure:
      scheduledLocal: 2026-10-24T07:30:00+03:00
      scheduledUtc: 2026-10-24T04:30:00Z
      revisedLocal: null
      terminal: null
    arrival:
      scheduledLocal: 2026-10-24T09:00:00+02:00
      scheduledUtc: 2026-10-24T07:00:00Z
      predictedLocal: 2026-10-24T08:56:00+02:00
      terminal: null
  live:                           # AviationStack, only from 2 h before departure
    status: active
    departure: {gate: "8", delayMinutes: 12, actualLocal: 2026-10-24T07:42:00}
    arrival:   {gate: A4, baggageBelt: "15", delayMinutes: 0, actualLocal: 2026-10-24T08:57:00}
- flightNumber: ZZ9999
  departureDate: 2026-10-24
  notFound: true                  # a stored miss, expires by negative-ttl
  scheduleFetchedAt: 2026-10-24T01:00:07.020Z
```

`data/flights/codeshares.yml` (table `codeshare_mappings`, key `bookedNumber`):

```yaml
- bookedNumber: KL2842
  operatingNumber: BT857
  resolvedAt: 2026-09-26T09:14:22.480Z
```

`data/api-usage.yml` (table `api_usage`, key `service` + `month`). It sits at the
top of `data/` beside `rates.yml` rather than under `data/flights/`, because it
is shared:

```yaml
- service: aerodatabox
  month: "2026-10"
  calls: 41
- service: aviationstack
  month: "2026-10"
  calls: 3
```

Months are quoted so YAML does not read `2026-10` as something other than text.
An old month's row is simply left in place as history.

Each has a repository interface with `Yaml…` and `Jdbc…` implementations behind
`feature-enable-database`, and one abstract contract test run against both, so a
behaviour that differs between the stores fails a test.

### Storage rules

- Each of the three has an interface with `Yaml…` and `Jdbc…` implementations
  behind `feature-enable-database`, and one abstract contract test run against
  both, like the other repositories.
- Flyway `V13` is schema only: the `flight` column on `itinerary_items` and the
  three tables (see Storage shape). `EveryField` fixtures cover the new entry field so the
  round-trip test fails if it is left unmapped.
- These caches are not per trip, so **deleting a trip does not delete them.**
  They hold no trip data, only public schedule facts, like rates.
- The importer copies the entry's `flight` document and verifies it. It does not
  import the caches (they are refetchable), like weather and rates.

## TTLs (AeroDataBox data, all configurable)

| Time to scheduled departure | TTL |
|---|---|
| More than 3 days | 72 h |
| Within 3 days | 24 h |
| Within 24 h | 1 h |
| After landing | Never refetched (frozen, like past weather days) |

AviationStack extras: about 15 minutes, only from 2 h before departure until
landed. **Landed** means AeroDataBox reports an arrived or landed status, or the
scheduled arrival plus 3 hours has passed, whichever comes first, so a flight whose
status never updates does not stay live forever. The negative (not found) cache is a separate setting.

Time to departure is computed from the record's scheduled UTC time. When there
is no record yet (first lookup), the entry's own date and the snapshot timezone
decide the tier.

**On a refresh failure the last record is served stale** with its "as of" time,
never blank, the same rule as weather.

## Which calls a public refresh makes

The flight is identified from the entry: its `operatingNumber` if it has one, else
the codeshare map's answer for its booked number, else the booked number itself,
together with the local date of its start. **A public refresh never calls
AviationStack to resolve a codeshare**, only for the live extras below, so a
reader's click cannot spend the scarce quota on resolution.

Decided inside the API, per request, from the current time and the record:

| Now relative to scheduled departure | Calls |
|---|---|
| More than 7 days before, or more than 2 days after (the entry's own departure) | None. The held record is served (stale once its TTL has lapsed), or nothing. |
| More than 2 h before | AeroDataBox only (if its TTL has lapsed) |
| 2 h before until landed | AeroDataBox (if lapsed), plus AviationStack (if its 15 min has lapsed, fewer than 8 attempts have been made for this operating flight and date, and its cap allows). AviationStack only adds fields. |
| After landing | None. The cached record is served. |

After an AeroDataBox failure the flight is backed off 15 minutes, then 1 hour
after a second failure in a row, then 6 hours from the third; any real answer
resets the count. That ladder is `app.circuit-breaker.back-off`, and the window
and attempt count are `app.flights.public-refresh.*` (see Configuration). The `ttlSeconds` sent to the page is what is left of the
record's TTL, cut at the next tier boundary or landing, at most the live TTL
while live, never under 60 s, and 300 s for a stale answer.

If AviationStack fails or its cap is reached, the response carries the AeroDataBox
data without the extras. If AeroDataBox's cap is reached, the cached record
(stale if need be) is served.

## Quotas

- `app.flights.aerodatabox.monthly-limit` = 400 and `…aviationstack.monthly-limit`
  = 100, each with `cap-percent` = 90. So the cap is 360 and 90.
- Every outbound call counts: the form's lookup (up to two AeroDataBox calls and
  one AviationStack call), a public refresh, and the nightly job.
- Past the cap a service is not called. The lookup answers `unavailable`, the page
  shows the cached record, nothing errors.
- A hostile reader cannot exhaust the cap by clicking one flight repeatedly. The
  guards, all per operating flight and date: no call outside 2 days before to 7
  days after departure; at most one AeroDataBox call per TTL while it answers,
  and while it fails a back-off of 15 min, 1 h, then 6 h; at most one
  AviationStack attempt per 15 min and 8 in all; one call at a time (`tryLock`).
  These are in memory, so a cold start forgets the back-off and the attempt
  count; the monthly cap is the backstop. The form's codeshare call is spent only
  after a fresh AeroDataBox miss, so retrying an unknown number costs nothing
  more within the 6 h negative TTL.

## Nightly job: `FlightPrewarmJob`

- Cron `app.flights.prewarm.cron`, a plain value in `application.yml` (not an
  environment setting; change it and redeploy), default `0 0 1 * * *`, zone UTC.
- Reads published trips through `TripRepository.findAllPublished()`. Selects
  flights departing within 72 hours **in published trips only**, deduped
  by (operating number, date), skipping any with a fresh record, soonest
  departure first, stopping at the AeroDataBox cap.
- Calls AeroDataBox only. AviationStack stays click-only.
- The run stops when AeroDataBox's service-wide circuit breaker
  (`app.circuit-breaker`, shared by every path) is open, which it is after 3
  consecutive failed calls, so an outage or a bad key does not burn through the list.
- **Never the only path.** On scale-to-zero Cloud Run the cron may not fire, so
  refresh-on-read is the real mechanism: a click refetches when the record is
  missing **or past its TTL**, and serves it as it is when fresh. The job only
  means fewer first clicks wait for a call. A failed job leaves nothing worse than a cold cache.
- Documented in `docs/scheduled/flights-prewarm.md` (schedule and zone, config
  key and variable, classes, failure behaviour, scale-to-zero note).
- There is no monthly-reset job, deliberately: the counter is month-keyed.

## API

- `GET /api/v1/trips/{id}/flights/lookup?number=&date=`: member only,
  `requireMember` at the top. Returns the fields to fill, the snapshot and the
  operating number, or `found: false`. Non-members get 404, as elsewhere.
- Public refresh endpoint, one per published flight, addressed by trip slug, flight
  number and local date (`GET /api/v1/public/trips/{slug}/flights?number=&date=`);
  the entry is found by matching the number, booked or operating, and the local
  start date. It returns `PublicFlightView` (no coordinates, no ICAO) and answers
  200, 204, 404 or 400 (`invalid_parameter`). It **serves only entries in a published trip**, and anything else is
  404, so it cannot be used as an open proxy. It sits under `/api/v1/…`, which
  the published page reaches on its own origin through the existing
  `functions/api/[[path]].js` forwarding Function (it adds `X-Proxy-Secret`), so
  no new Cloudflare code is needed. It is a GET, so it needs no CSRF token, and
  it must be permitted without a login in `SecurityConfig`. Locally the API
  serves both the page and the endpoint on :8080.
- The public endpoint returns only flight status for that entry's flight, never the
  entry itself. It needs a real number and date on a published trip's entry, so it
  does not undo the per-viewer narrowing of personal pages.
- **Throttling of the public path** (the anti-exhaustion requirement), inside
  `FlightStatusService`: the match against a published entry comes first, so a
  caller cannot grow any map with keys of their own; an AviationStack attempt is
  stamped before the call and holds for the live TTL whatever the result, keyed by
  operating flight and date and capped at 8 per flight and date; AeroDataBox
  failures back the flight off 15 minutes, then 1 hour, then 6 hours, reset by a
  real answer; no call outside 2 days before to 7 days after departure; a per-key `tryLock`
  gives one call at a time per flight (others get the held record); the maps are
  bounded and in memory, so this is single-instance and resets on restart. The
  member lookup is never throttled by it. `PasswordChangeGate` exempts
  `/api/v1/public/`, and `GlobalExceptionHandler` answers 400 `invalid_parameter`
  for parameter binding errors.
- Both are read-only GETs. OpenAPI is regenerated (`openApiUpdate`) and
  `OpenApiDocTest` must pass.
- Errors use `ApiException` with message keys, never prose.

## Published page changes

- `PublishedTrip` gains, per transport entry, the flight number as booked, the
  operating number, the snapshot airports and terminal. `StaticSiteRenderer`
  builds it by hand as it does everything else, so nothing else about the entry
  or its audit fields leaks.
- Personal pages are narrowed by viewer as before, so a flight ships only on the
  pages whose viewer can see that entry (`Travellers.includes`), searched for in
  the whole file by the existing style of test, not the markup.
- New `page.*` message keys in `messages_en.properties`, inlined as before.
- `page.js` gains the refresh icon and the browser cache (`localStorage`, keyed by
  flight number and date, holding the server's TTL). Wrapped in try and catch.

## Frontend changes

### Shared module

`js/pages/trip/flight-lookup.js`, a factory merged into the page like the tab
factories and like `member-picker.js`, which exists because forms name the same
field differently. The Plan form uses `planForm` and the Itinerary tab uses
`entryForm`, so the module takes accessors for each form's own fields and holds
the state once: the lookup status (idle, busy, found, notFound, unavailable), the
note text and the pending snapshot. Merged with `Object.defineProperties` and
`getOwnPropertyDescriptors`, not spread, for the getter reason in CLAUDE.md.

### Where and when things show

| Piece | Shows when |
|---|---|
| Flight number field | Transport category only. Plan form: the checklist item's category. Itinerary form: the category select. If the select changes to something else, the field hides and the pending snapshot is dropped. |
| Lookup icon | Beside the field. Enabled with a number and a start date. Has an accessible name. |
| Note under the field | After a lookup, or when editing an entry that already has a saved flight (drawn from the stored snapshot, no call). |
| Miss or failure line | After a lookup that found nothing or could not run. Never blocks saving. |
| Chip with number and route on the plan card (checklist drawer) and the Itinerary row | Whenever the entry has a flight. Reads like "KL2842 · TLL to AMS". No live status in the planner. |

- The flight number and date sit directly above the date and time rows, because
  the lookup fills those.
- The edit paths prefill the saved number and show its note (`openEditEntry`,
  `editPlan`), and keep the existing rule that a blank time on an all-day entry is
  prefilled blank, not `00:00`.
- Payload: `flight: {number, operatingNumber, from, to, terminalFrom, terminalTo}`
  with the three answers (absent leaves alone, empty number clears, a value sets),
  sent only when the member touched the field or did a lookup.
- Every dialog change carries `x-dialog` and `aria-labelledby` as CLAUDE.md
  requires, and none is new here since both forms exist already.
- Dirty tracking: a lookup on its own does not mark the drawer dirty; the fields it
  fills do, as if typed.
- All text goes through `t()` keys in `js/i18n/en.js`, plurals by `key.one` and
  `key.other`, and `npm run check` must pass. Phone width: number and icon stay on
  one row and the note wraps below.
- `js/api.js` gains `lookupFlight(tripId, number, date)`.

### Files

`trip.html` (both forms, the plan card, the itinerary row),
`js/pages/trip/flight-lookup.js` (new), `checklist.js`, `itinerary.js`, `js/api.js`,
`js/i18n/en.js`, a small SCSS component, and on the published side `page.js`,
`page.css` and `messages_en.properties`. The published refresh icon and the
"Updated" line are described under Published page.

### Build order

API lookup endpoint first, then the shared module, the Plan form, the Itinerary
form, the chips, and last the published page.

## Configuration

A new `@ConfigurationProperties` record, `FlightProperties`, registered on its own
config class and never added to `AppProperties`. It holds, for each service, the
base URL, key, monthly limit and cap percent, plus the TTL tiers, the AviationStack
window and TTL, the negative TTL, the prewarm cron and the public refresh limits
(`public-refresh`: `window-before` 2d, `window-after` 7d,
`max-live-attempts-per-flight` 8, `stale-browser-ttl` 5m). A missing key means the
feature is off for that service, not that the app fails to start.

**Only the two keys are environment settings.** `key: ${AERODATABOX_KEY:}` and
`key: ${AVIATIONSTACK_KEY:}` are the only placeholders in the `app.flights` block;
every tunable is a plain value in `application.yml`, shipped with the image and
changed by editing the file and redeploying (Spring's relaxed binding can still
override one per deployment, e.g. `APP_FLIGHTS_PREWARM_CRON`, but that is not the
standard route). Locally the keys are sourced from `planner-api/.env.aerodatabox`
and `.env.aviationstack`, one git-ignored file per service like `.env.r2` and
`.env.resend`; in the cloud each is a Secret Manager secret created from that same
file and attached by `deploy.sh` only if it exists. The keys never reach the
frontend or a published page.

**Circuit breaker.** A service-agnostic `resilience/CircuitBreaker`, configured
under `app.circuit-breaker` (`failure-threshold` 3, `back-off` [15m, 1h, 6h],
optional `services.<name>` overrides) in its own record `CircuitBreakerProperties`.
Both clients check it before spending quota (enabled, then breaker, then the cap)
and report each attempt: an unavailable answer is a failure, found or a genuine
"no such flight" a success; no key, cap and an open breaker are not attempts. The
public refresh's per-flight back-off uses the same ladder, keyed `operating|date`,
and counts only a call that actually went out for that flight. After a pause
exactly one trial call is let through (claimed atomically, 30 s trial timeout);
bad settings are replaced with a WARN.

## Failure behaviour

- No key, cap reached, timeout, 4xx or 5xx from either service: the lookup returns
  `unavailable` (never `notFound`, which is reserved for a real empty answer), and
  the public refresh returns the cached record or nothing. Neither ever breaks a
  save or a page.
- A read timeout is treated as throttling, not as a bug in the request (the same
  lesson recorded for the weather service).
- The AviationStack key goes over plain HTTP on the free plan. Accepted for a free
  key; rotate it and switch the base URL to HTTPS if the plan is upgraded.

## Testing

- Clients tested against canned responses (`CannedHttp`), never the live services:
  found, empty body, 403, timeout, a codeshared row, a row with a different date.
- `FlightLookup`: direct hit, codeshare route, stored mapping skips AviationStack,
  miss cached, cap reached.
- TTL tiers, frozen after landing, stale served on failure, the 2 hour window.
- Counter: month rollover needs no reset, increments are not lost under
  concurrency, the cap stops calls.
- Nightly job: published trips only, dedupe, skips fresh records, soonest first,
  stops at the cap.
- Repository contract tests for the three stores in both YAML and Postgres.
  Check the `skipped` count is 0 (Testcontainers).
- Entry field: round trip, patch semantics (absent, empty, value), spread days
  carry none.
- Published page: the flight numbers ship, another viewer's flights do not, and no
  key or AviationStack field the page does not draw is in the file.

## Documentation to write in the same change

- `docs/external-apis/aerodatabox.md` and `docs/external-apis/aviationstack.md`
  (what, base URL and auth, each call with its trigger and frequency, caching,
  failure behaviour, quirks including the mislabelled UTC and HTTP-only key),
  plus table rows in the README there.
- `docs/scheduled/flights-prewarm.md`.
- `docs/api/openapi.yaml`, regenerated.
- The `free-tier-usage` skill learns about both quotas.
- The CLAUDE.md section on the flight module, once built.

## Decisions taken in review

1. **Live flight details on a published page are on by default and have no
   account setting.** Flight status is public information. This differs from
   cost, destination days and forecast, which default to off because they are
   private. If it is ever wanted, a `flightStatus` flag in
   `PublishedPageSettings` is a small addition and the renderer would leave the
   flight out of the file when off.
2. **The public endpoint goes through the existing `/api/*` proxy**, as
   described under API.
