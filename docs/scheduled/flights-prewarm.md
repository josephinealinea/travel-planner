# Flights prewarm

Warms the flight cache once a night so the first reader to refresh a flight on a
published page does not wait on an outside call.

## Schedule
Every day at **01:00 UTC**.

## Configuration
- Property `app.flights.prewarm.cron`, a plain value in
  `planner-api/src/main/resources/application.yml`. To change the schedule, edit it
  there, bump `IMAGE_TAG` and redeploy; it is not an environment setting and does
  not belong in `.env.deploy`. Spring's relaxed binding means the environment
  variable `APP_FLIGHTS_PREWARM_CRON` would also override it, but that is not the
  standard route, and on Cloud Run a variable set by hand is wiped by the next
  `./deploy.sh`.
- Default `0 0 1 * * *` lives in `application.yml`, in `FlightProperties.Prewarm`,
  and as the fallback in the `@Scheduled` placeholder.
- The cron zone is fixed to UTC in the annotation.

## What it does
1. Lists every published trip (`TripRepository.findAllPublished`).
2. Takes each itinerary entry that has a flight and departs within 72 hours.
3. Dedupes by operating flight number and departure date, so a flight on three trips is one call.
4. Sorts soonest first and, for each, asks `FlightData.schedule`, which skips a fresh record.
5. Stops when the AeroDataBox monthly cap is reached, or when AeroDataBox's circuit
   breaker is open.

AviationStack is never called: it is click-only, inside its two-hour window.

## Classes
- `FlightPrewarmJob`: the cron and the selection.
- `AeroDataBoxClient`: makes the call and enforces the monthly cap.
- `CodeshareRepository`: resolves the operating number for a booked codeshare number.
- `FlightData`: the cache-aware AeroDataBox access; decides whether a call is needed.
- `FlightFreshness`: the TTL tiers (72 h, 24 h, 1 h) and the landed rule.
- `ApiUsageService`: counts calls against the monthly cap.
- `CircuitBreaker` (`resilience/`): pauses AeroDataBox after consecutive failed
  calls; the job stops when it is open. Settings: `CircuitBreakerProperties`.

## Failure behaviour
**Circuit breaker.** The run stops as soon as AeroDataBox's service-wide circuit
breaker is open ([circuit-breaker.md](../external-apis/circuit-breaker.md)): after
`app.circuit-breaker.failure-threshold` (3) consecutive failed calls, whichever
path made them, and a streak resets only on a real answer. If readers' clicks have
already opened it, the run makes no call at all. Each failed attempt still counts
against the monthly cap, so the breaker bounds an outage's cost to 3 calls rather
than one per remaining flight. A flight served from a fresh record is not a call
and neither resets nor extends the streak.

**Timezone fallback.** An entry whose snapshot has no airport timezone (or an
unreadable one) is read as UTC. The departure instant can then be off by up to
about 14 hours either way; this is harmless because warming is best-effort and
the cache key is the local date. A malformed entry is logged and skipped and
never aborts the run.

The default cron (`0 0 1 * * *`) matches `FlightProperties.Prewarm`.

A failure on one flight is logged and the rest continue. A failed run leaves a cold
cache and nothing worse: nothing depends on it having run.

## Scale-to-zero on Cloud Run
The CPU exists only while a request runs, so this cron may never fire. **Refresh on
read is the real mechanism**: a reader's click refetches when the record is missing
or past its TTL. The job only means fewer first clicks wait for a call. There is no
monthly reset job either: the usage counter is keyed by month, so a new month
starts at zero by itself.
