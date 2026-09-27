# External services

Everything the planner talks to outside its own process, what triggers each
call, and how often it can happen. The API's own endpoints are in
[`../api/openapi.yaml`](../api/openapi.yaml).

| Service | Called from | Auth | Triggered by | Stored? |
|---|---|---|---|---|
| [countries.dev](countries-dev.md) | API | none | typing a destination; saving one with a country | in memory, per process |
| [Open-Meteo forecast + climate](open-meteo.md) | API **and** the reader's browser | none | opening a trip; opening a published page | per-trip cache (only while the trip is under way) |
| [open.er-api.com](exchange-rates.md) | API | none | startup, daily cron, any read of a stale table | `rates.yml` / `rates` table |
| [AeroDataBox](aerodatabox.md) | API | RapidAPI key (`AERODATABOX_KEY`) | the entry form's flight lookup; a reader refreshing a published flight; nightly [prewarm](../scheduled/flights-prewarm.md) | `flight_records` table (`data/flights/records.yml`) |
| [AviationStack](aviationstack.md) | API | `access_key` query parameter (`AVIATIONSTACK_KEY`, HTTP only) | a codeshare the lookup cannot find; a reader refreshing a flight within 2 h of departure | `codeshare_mappings`; live extras on the flight record |
| [Cloudflare R2](cloudflare-r2.md) | API, Cloudflare Function | S3 keys / binding | publishing; a public reader opening `/p/*` | it *is* the store |
| [SMTP](email-smtp.md) | API | user/password | membership, publish and payment events | no |
| [Neon Postgres](neon-postgres.md) | API | connection string | every request, in database mode | it *is* the store |
| Google Maps | browser link only | none | member clicks a link | no |

Every outbound call from the API is wrapped so a failure never breaks the page
that asked: it returns empty or the last known value. Nothing here is a gate.
AeroDataBox and AviationStack also sit behind the shared
[circuit breaker](circuit-breaker.md) (`app.circuit-breaker`), which pauses a
failing service instead of retrying it on every request.

## Every call is logged

Each HTTP call the API makes to AeroDataBox, AviationStack, Open-Meteo,
open.er-api.com and countries.dev is logged at **INFO** under the logger
`external-api` (`shared/HttpCallLog`, attached to each client's `RestClient`):

```
AeroDataBox request: GET https://aerodatabox.p.rapidapi.com/flights/number/AV105/2026-10-31?dateLocalRole=Departure
AeroDataBox response: 200 https://…/AV105/2026-10-31?… in 412 ms (2318 chars): [{"departure":…
```

A failed call logs the exception instead of a response. Headers are never
logged (RapidAPI's key travels in one) and secret-looking query parameters
(`access_key`, `key`, `api_key`, `token`) are masked as `***`. Bodies are cut at
2000 characters, with the full length still shown. To read only these lines, filter
the log for `external-api`. R2 and SMTP go through their own SDKs and are not
covered; a new HTTP client should be built through `HttpCallLog.on(...)`.

## Not calls

- **Google Maps**: `planner-web/js/pages/trip/destinations.js` builds a
  `google.com/maps/search/` link the member clicks. No API, no key.
- **Cloudflare proxy** (`functions/api/[[path]].js`) forwards `/api/*` to Cloud
  Run with `X-Proxy-Secret`. That is our own API behind a proxy, not a third party.

## Adding or changing an external call

These files are written by hand, so keep them honest:

- **Changing** a client, timeout, TTL or trigger: update that service's file and
  its row in the table above in the same change.
- **Adding** an external service: create `docs/external-apis/<service>.md`
  (what it is and its auth, base URL and config key, each call with what
  triggers it and how often, caching/storage, failure behaviour, quirks), add a
  row to the table above, and link it.
