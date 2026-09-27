# Circuit breaker

One breaker for every outside service that adopts it, in
`planner-api/.../resilience/` (`CircuitBreaker`, `CircuitBreakerProperties`). Today
[AeroDataBox](aerodatabox.md) and [AviationStack](aviationstack.md) use it; the
other clients do not yet (see CLAUDE.md, *Adopting the circuit breaker in another
client*). It makes no calls of its own.

## Settings
Plain values in `planner-api/src/main/resources/application.yml`, not environment
settings. Change one there and redeploy. Spring's relaxed binding also lets an
environment variable override a property without a placeholder
(`APP_CIRCUIT_BREAKER_FAILURE_THRESHOLD`), but that is not the standard route, and
on Cloud Run a variable set by hand is wiped by the next `./deploy.sh`. The
defaults are also in the record, so an absent block behaves the same.

A value that would quietly break the breaker is replaced at startup with a WARN
naming the property and what it became: a threshold below 1 becomes 3; a zero or
negative rung is dropped (a `0s` pause would never open anything); a ladder left
empty, or written as `[]`, is the default one. The same applies inside a
per-service override.

| Key | Default | Meaning |
|---|---|---|
| `app.circuit-breaker.failure-threshold` | `3` | Consecutive failed calls before a service is paused |
| `app.circuit-breaker.back-off` | `[15m, 1h, 6h]` | The pause on each opening, escalating; the last rung repeats. Also the per-key retry delay |
| `app.circuit-breaker.services.<name>.failure-threshold` / `.back-off` | absent | A per-service override; an omitted field inherits the global one. Shown as a comment in the yml |

Service names are the clients' `SERVICE` constants: `aerodatabox`, `aviationstack`.

The public flight refresh has its own limits under `app.flights.public-refresh`:

| Key | Default | Meaning |
|---|---|---|
| `window-before` | `2d` | No lookup for a flight that departed longer ago than this |
| `window-after` | `7d` | Nor for one departing further ahead than this |
| `max-live-attempts-per-flight` | `8` | AviationStack attempts per operating flight and date, across all readers |
| `stale-browser-ttl` | `5m` | How soon a page served something stale asks again |

## Behaviour
- **Service-wide.** After `failure-threshold` consecutive failed calls the service
  is *open*: the client answers `unavailable` without calling and without spending
  quota. When the pause lapses exactly one trial call goes out: the first caller
  claims it atomically and every other caller still sees the service open until
  the trial reports, or until 30 s pass with no report (`TRIAL_TIMEOUT`, so a lost
  trial cannot wedge it), when another trial may go. If the trial fails the
  breaker re-opens on the next rung; if it succeeds it closes and both the streak
  and the ladder reset. Failures reported while already open change nothing.
  `paused(service)` asks the same question without claiming the trial.
- **Per key.** The public flight refresh backs off one flight and date
  (`operating|date`) on the same ladder: the 1st failure waits `back-off[0]`, the
  2nd `back-off[1]`, the 3rd and later the last rung; a real answer resets it.
  Only a call that actually went out for that flight counts: a refresh made while
  the service is paused, with no key or past the cap records nothing, so the
  flight is looked up again as soon as the service recovers. No trial is claimed
  per key (one caller at a time holds the flight's lock).
- **What counts.** A failure is a call that was made and got no real answer
  (timeout, connection error, 429/5xx, other non-200s, a 200 with an unreadable or
  error body), except AeroDataBox's 404 and 204, which mean "no such flight" and
  count as a success. "No such flight" is an answer, so it is a success. No key, the
  monthly cap and the breaker itself are not attempts and are never counted.
- **In memory.** Single-instance (YAML mode and Cloud Run both run one), reset by a
  restart; services are a handful of names and keys are trimmed past 1000.
- **Logs** one INFO line when a service opens and one when it answers again,
  naming the service only, never a key or URL.
