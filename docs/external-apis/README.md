# External services

Everything the planner talks to outside its own process, what triggers each
call, and how often it can happen. The API's own endpoints are in
[`../api/openapi.yaml`](../api/openapi.yaml).

| Service | Called from | Auth | Triggered by | Stored? |
|---|---|---|---|---|
| [countries.dev](countries-dev.md) | API | none | typing a destination; saving one with a country | in memory, per process |
| [Open-Meteo forecast + climate](open-meteo.md) | API **and** the reader's browser | none | opening a trip; opening a published page | per-trip cache (only while the trip is under way) |
| [open.er-api.com](exchange-rates.md) | API | none | startup, daily cron, any read of a stale table | `rates.yml` / `rates` table |
| [Cloudflare R2](cloudflare-r2.md) | API, Cloudflare Function | S3 keys / binding | publishing; a public reader opening `/p/*` | it *is* the store |
| [SMTP](email-smtp.md) | API | user/password | membership, publish and payment events | no |
| [Neon Postgres](neon-postgres.md) | API | connection string | every request, in database mode | it *is* the store |
| Google Maps | browser link only | none | member clicks a link | no |

Every outbound call from the API is wrapped so a failure never breaks the page
that asked: it returns empty or the last known value. Nothing here is a gate.

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
