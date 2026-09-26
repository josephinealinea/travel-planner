# Exchange-rate refresh

Keeps the install's one exchange-rate table current, so budget totals convert
without anyone waiting on the provider. Source and call details:
[open.er-api.com](../external-apis/exchange-rates.md).

## What it does

Fetches `GET /v6/latest/EUR` from open.er-api.com, keeps only the currencies in
`app.currencies.available`, and stores the table (`data/rates.yml`, or the
`rates` table in database mode). One table for the whole install, not per trip.

## When it runs

| Trigger | When |
|---|---|
| Startup | once, when the application is ready, on its own thread so booting never waits on the provider |
| Cron | daily at **01:30 UTC** by default, an hour after the provider's own update (~00:30 UTC) |
| Refresh on read | not a schedule: any read of a table older than 24 h (or never fetched) refreshes it synchronously. Exists because Cloud Run may never run the cron |

Asking more than once a day returns identical numbers.

## Changing the schedule

| Setting | Where | Default |
|---|---|---|
| `app.rates.cron` | `planner-api/src/main/resources/application.yml` | `${RATES_CRON:0 30 1 * * *}` |
| `RATES_CRON` | environment variable, overrides the default | unset |

A Spring six-field cron (`second minute hour day month weekday`), always
evaluated in **UTC** (`zone = "UTC"` on the annotation). Related, not schedule:
`app.rates.base`, `app.rates.timeout` (10 s), `app.rates.base-url`. The 24 h
staleness limit and 15 min retry back-off are constants in `RatesService`
(`MAX_AGE`, `RETRY_AFTER_FAILURE`), not config.

## Storage, with the database on or off

The job behaves the same in both modes; only where the table is saved differs.
`RatesService` talks to the `RatesRepository` interface and never touches files
or SQL, and `feature-enable-database` picks the implementation.

| `feature-enable-database` | Repository | Where the table lives |
|---|---|---|
| `false` (default, local) | `YamlRatesRepository` | `data/rates.yml` |
| `true` (cloud) | `JdbcRatesRepository` | the `rates` table in Postgres |

The process in either mode:

1. **Startup:** an async thread calls `refresh()`. The API stays up if the
   provider is down.
2. **Until that lands:** `current()` returns the table held in memory. On first
   use after a restart it loads whatever the last refresh saved (`rates.yml` in
   YAML mode), normally yesterday's table rather than nothing.
3. **Refresh:** a successful fetch replaces the in-memory table and saves it
   through the repository. A failed fetch keeps the old table.
4. **Daily cron:** the same `refresh()`.
5. **Read of a table older than 24 h:** `current()` refreshes synchronously.

In YAML mode, which is single-instance only, a fresh clone with no `data/` folder
starts with an empty table, so conversions read as "missing rate" until the
startup fetch completes, usually within seconds. If that first fetch fails, they
stay that way until the next successful one.

## Java classes

| Class | Purpose |
|---|---|
| `rates/RatesRefresher` | The scheduler. `refreshOnStartup()` (`@EventListener(ApplicationReadyEvent)`, run async) and `refreshOnSchedule()` (`@Scheduled(cron = "${app.rates.cron}", zone = "UTC")`) both just call `RatesService.refresh()` |
| `rates/api/RatesService` | Holds the table. `refresh()` fetches and saves; `current()` serves the table and triggers the refresh-on-read. Owns the lock and back-off |
| `rates/ExchangeRatesClient` | The HTTP call to open.er-api.com; returns null on any failure |
| `rates/RatesConfig` | Builds the HTTP client and carries `@EnableScheduling` |
| `rates/infra/RatesRepository` | Storage, YAML or JDBC, behind one interface |

## Failure behaviour

A failed fetch never clears the held table: rates a day old are a rounding
difference, no rates makes every total unconvertible. After a failure (from the
cron or a read), reads do not retry for 15 minutes so an outage cannot add the
provider's timeout to every request. Only one reader fetches at a time
(`tryLock`); the others get the held table at once.

## Cloud Run

With one instance scaling to zero the cron and startup thread are best-effort;
the refresh-on-read is what guarantees a table under 24 h old.
