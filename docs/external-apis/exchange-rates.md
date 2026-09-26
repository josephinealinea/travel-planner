# Exchange rates (open.er-api.com)

Free, no key. `GET /v6/latest/EUR` (`app.rates.base-url`, `app.rates.base`),
timeout 10 s. Code: `rates/ExchangeRatesClient`, `RatesService`, `RatesRefresher`.

One table for the whole install, not per trip: 166 currencies from the
provider, filtered to `app.currencies.available` on the way in.

| Trigger | Frequency |
|---|---|
| Startup | once, on its own thread; booting never waits on it |
| Cron `app.rates.cron` (default `0 30 1 * * *` UTC) | once a day, an hour after the provider updates |
| Any read of a table older than 24 h (or never fetched) | synchronous, so that free-tier Cloud Run, which has no CPU between requests, still refreshes |

Fences on the read path: only one reader fetches at a time (`tryLock`; the
rest get the held table); after a failure reads do not retry for 15 minutes;
a failed fetch keeps the old table. Expected volume: about one call a day.
The provider answers `200` with `result:"error"` for a bad base, so the body is
checked, not just the status.

Frontend never calls it: `GET /api/v1/rates` and the budget view carry the
table and its date.


Back to the [overview](README.md).
