# AeroDataBox

The source of truth for a flight: schedule, airports, times with real offsets,
terminal, status and aircraft.

> **Not yet verified live.** The RapidAPI base URL and the `X-RapidAPI-Key` /
> `X-RapidAPI-Host` headers below were written from the provider's public
> documentation and the canned payloads in the tests. They had **not** been checked
> against the live service when this file was written, so treat them as
> assumptions until the first owner-approved live call has confirmed them, then
> delete this note.

- **Base URL:** `https://aerodatabox.p.rapidapi.com` (`app.flights.aerodatabox.base-url`).
- **Auth:** RapidAPI headers `X-RapidAPI-Key` (the key, the environment variable
  `AERODATABOX_KEY`: locally from `planner-api/.env.aerodatabox`, on Cloud Run from
  the Secret Manager secret `aerodatabox-key`) and `X-RapidAPI-Host` (the base URL's
  own host). No key means the service is off, not that the app fails. The key is
  the only environment setting; every other value below is a plain property in
  `application.yml` under `app.flights`, changed by editing the file and redeploying.
- **Client:** `AeroDataBoxClient`. Timeout 8 s (`app.flights.aerodatabox.timeout`).
  Its exception messages carry no key.

## Calls
| Call | Trigger | Frequency |
|---|---|---|
| `GET /flights/number/{number}/{date}?dateLocalRole=Departure` | The entry form's lookup icon (`GET /api/v1/trips/{id}/flights/lookup`, member only; up to two AeroDataBox calls per click, the booked number then its operating number after a codeshare is resolved, plus at most one AviationStack call); a reader's refresh on a published flight (`GET /api/v1/public/trips/{slug}/flights?number=&date=`) when the cached record is missing or past its TTL; the nightly [prewarm](../scheduled/flights-prewarm.md) | On click or nightly, deduped by flight and date |

## Quota
Free tier **400 calls a month**. The app stops at **90% (360)** so the last calls
stay free. Both numbers are configurable: `app.flights.aerodatabox.monthly-limit`,
`app.flights.aerodatabox.cap-percent`. Counted in `api_usage` (locally `data/api-usage.yml`) by
UTC calendar month; a new month is a new row, so no reset job exists. The check and
the increment are one operation (`ApiUsageRepository.tryAcquire`, reached through `ApiUsageService.tryAcquire(service, cap)`), and a call counts when
attempted, even if it fails.

## Caching
`flight_records` (locally `data/flights/records.yml`), install-wide, keyed by
operating number and local departure date. TTL by time to departure: more than 3
days 72 h, within 3 days 24 h, within 24 h 1 h (`app.flights.ttl.far-ahead`,
`within-three-days`, `within-one-day`). Frozen once landed.
A genuine empty answer is stored as a miss for `app.flights.negative-ttl` (6 h), except
over a good record already held: then nothing is stored and the held record is
served stale (and counts as a failure for the public back-off), so one blip
cannot blank a known flight. A failed refresh serves the last record marked stale.
The public answer's `ttlSeconds` (how long the page's browser keeps it) is what
is left of the record's TTL, cut at the next tier boundary (72 h, 24 h, the live
window, landing), at most 15 min while live, never under 60 s; 300 s when stale
(`app.flights.public-refresh.stale-browser-ttl`). Only the mapping and record
tables are stored; the entry's own `flight` snapshot (`itinerary_items.flight`) is a
separate, member-editable copy.

## Failure behaviour
No key, cap reached, circuit breaker open, timeout, 4xx (except 404 and 204, which
mean "no such flight" and are NOT_FOUND, a success for the breaker), 5xx: the lookup answers
`unavailable` and the public refresh returns the cached record or nothing. Never
stored as a miss.

**Circuit breaker** ([circuit-breaker.md](circuit-breaker.md)). After 3
consecutive failed calls (`app.circuit-breaker.failure-threshold`) AeroDataBox is
paused for 15 minutes, then 1 hour, then 6 hours on each re-opening
(`app.circuit-breaker.back-off`); a success closes it. While paused the client
answers `unavailable` **before** spending quota, for every path: the member's form,
the public refresh and the prewarm. A genuine "no such flight" is an answer and
closes it; no key and the monthly cap are not attempts and never trip it.

The public path is throttled inside `FlightStatusService`, because anyone holding
a published slug can call it: it calls only for an entry departing between 2 days
ago and 7 days ahead (`app.flights.public-refresh.window-before` / `window-after`),
and outside that serves the held record (stale once its TTL has lapsed) or nothing;
failures back the flight off 15 minutes after the first, 1 hour after the second
and 6 hours from the third on (the breaker's `back-off` ladder, per flight and
date), reset by any real answer (found or a genuine empty one). Only a call this
flight actually made counts: a refresh while the service breaker is paused, with
no key or past the cap records nothing, so the flight is asked again as soon as
the service recovers;
concurrent readers of one flight share a single call (a per-key
`tryLock`, the others get the held record); the tracking maps are bounded and
in memory, so the throttle is single-instance and resets on restart. The member
lookup is never held back by that per-flight back-off, only by the service-wide
breaker. The public endpoint answers 200 (a
record), 204 (nothing to show yet), 404 (no such flight on a published trip) or 400
(bad parameter, `invalid_parameter`), and its `PublicFlightView` carries no
coordinates and no ICAO code.

## Quirks
- **"No such flight" is an empty body**, not a 404 or an error.
- A codeshare number (KL2842) is not found; the operating flight (BT857) is. Codeshare
  resolution is AviationStack's job ([aviationstack.md](aviationstack.md)).
- Times are `2026-10-24 07:30+03:00` (a space, not a `T`); the app converts and
  fills the airport's local wall-clock time, never `utc`.
- A read timeout means throttling, not a bug in the request.
- The airport board endpoint reports `codeshareStatus: Unknown` for most Tallinn flights.
- **One number can fly several legs on a day.** AV 105 on 2026-10-31 is Bogotá to
  Cusco (08:00) and Cusco to La Paz (12:30), both answers of one call. The client
  returns every leg whose local departure date matches, earliest first
  (`AeroDataBoxClient.legs`); the cache holds them all in one record (`schedule` is
  the first, `other_legs` the rest), so a second leg costs no extra call. The form
  lookup offers a pick only when there is more than one; the entry then keeps the
  chosen leg's airports, and the public refresh shows the leg leaving that entry's
  departure airport. A record's freshness follows the first leg not yet landed, so
  a landed first leg does not freeze a later one.
