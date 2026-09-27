# AviationStack

Used for two things only: finding a codeshare's operating flight, and live extras
(gate, baggage belt, delay, actual times) close to departure. Never for scheduled
times or airports.

- **Base URL:** `http://api.aviationstack.com/v1` (`app.flights.aviationstack.base-url`).
- **Auth:** query parameter `access_key` (the environment variable `AVIATIONSTACK_KEY`:
  locally from `planner-api/.env.aviationstack`, on Cloud Run from the Secret Manager
  secret `aviationstack-key`; the only environment setting, every other value here
  is a plain property under `app.flights` in `application.yml`). **The free plan is HTTP
  only, so the key travels in plain text.** Rotate it and switch the base URL to HTTPS
  if the plan is upgraded. Because the key is in the URL, the client redacts it from
  every exception message, so it cannot reach a log; it is never logged otherwise.
  No key means the service is off, not that the app fails.
- **Client:** `AviationStackClient`. Timeout 8 s (`app.flights.aviationstack.timeout`).

## Calls
| Call | Trigger | Frequency |
|---|---|---|
| `GET /flights?flight_iata={number}` (codeshare) | The form's lookup (member only), only when AeroDataBox has just been asked and answered empty (a stored miss is not enough) and no mapping is known | At most once per number per negative-TTL window (6 h) while AeroDataBox keeps answering empty; never again once a pairing is stored |
| `GET /flights?flight_iata={number}` (live) | A reader's refresh on a published flight, only from 2 h before departure until landed, when the last live attempt is over 15 min old | At most every 15 min per operating flight and date, and at most 8 attempts per flight and date (`app.flights.public-refresh.max-live-attempts-per-flight`) |

The nightly prewarm never calls it.

## Quota
Free plan **100 calls a month**; the app stops at **90%**. `app.flights.aviationstack.monthly-limit`,
`app.flights.aviationstack.cap-percent`. Counted in `api_usage` like AeroDataBox.

## Caching
Codeshare pairings: `codeshare_mappings` (locally `data/flights/codeshares.yml`),
permanent. Live extras: on the flight's `flight_records` row with their own fetch
time, TTL `app.flights.live.ttl` (15 m), window `app.flights.live.window` (2 h).

## Failure behaviour
Unavailable is never a miss. Without the extras the page still shows the AeroDataBox
data.

**Circuit breaker** ([circuit-breaker.md](circuit-breaker.md)): 3 consecutive
failed calls (a timeout, a non-200, an `error` object, a 200 without a `data`
array) pause AviationStack for 15 minutes, then 1 hour, then 6 hours; while paused
neither the codeshare question nor the live extras spend a call. An empty `data`
array is an answer and closes it. Its threshold can be set on its own with
`app.circuit-breaker.services.aviationstack.failure-threshold`.

While AviationStack is paused the public path neither calls nor stamps an
attempt, so a pause does not use up a flight's 8. Otherwise, on the public path an AviationStack attempt is stamped **before** the call and
holds for the live TTL whatever the result (found, empty or failed), so a miss or an
outage cannot cost another call on every click. The guard is keyed by operating
flight and date, like the record, so two codeshare numbers for one flight share it;
after 8 attempts for one flight and date no further call is made and readers get
the live extras already held. That throttle is in memory, bounded,
single-instance, and resets on restart.

The codeshare question is not stored when the answer is "no codeshare", so what
stops a retried lookup from asking again is AeroDataBox's stored miss: while it
is fresh (6 h) the lookup answers not found without calling AviationStack. An
AviationStack outage on the first try therefore also reads as not found until
the miss expires.

## Quirks
- **It labels airport local time as UTC:** `07:30+00:00` for a 07:30 Tallinn
  departure. The offset is dropped, and its scheduled and estimated times are never used.
- The free plan returns only flights around today (yesterday and today for a daily
  flight). A `flight_date` filter answers **403 `function_access_restricted`**.
- Errors arrive as an `error` object with a 200 or a 403, so the status code alone
  is not enough.
- A codeshare appears as its own row whose `flight.codeshared` names the operating
  flight (`bt857`, lower case). Rows with no codeshare are treated as not found.
