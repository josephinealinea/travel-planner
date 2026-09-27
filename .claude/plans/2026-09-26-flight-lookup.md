# Flight Lookup and Live Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **No git steps.** The owner writes their own git history and a hook blocks commits. Every task ends with a **Checkpoint** (tests green), never a commit. Do not run `git commit`, `git branch` or `git stash`.

**Goal:** A member types a flight number on a transport plan, one click fills the times and saves the airports, and a published page can refresh each flight for live status, including when the ticket number is a codeshare.

**Architecture:** A new `flights/` module in the API owns two clients (AeroDataBox for schedule and status, AviationStack for codeshare resolution and live extras), an install-wide cache with tiered TTLs, a member-only lookup endpoint, a public refresh endpoint and a nightly prewarm job. A shared `usage/` module counts calls per service per month so free-tier caps hold. The itinerary entry stores a small `flight` snapshot; everything volatile lives in the cache.

**Tech Stack:** Spring Boot 3.5 / Java 21, Jackson (YAML and JSON), Spring `JdbcClient`, Flyway, JUnit 5 + AssertJ, Alpine.js, Sass, vanilla JS for the published page.

**Spec:** `.claude/specs/2026-09-26-flight-lookup-design.md`

## Deviations from the spec (apply to the spec in Task 14)

1. **The public endpoint is addressed by trip slug, flight number and date, not by entry id.** `PublishedTrip.Entry` carries no id, and shipping ids into a public file buys nothing. The server finds the entry by matching number (booked or operating) and local start date inside that published trip. Route: `GET /api/v1/public/trips/{slug}/flights?number=&date=`.
2. **`TripRepository` gains `findAllPublished()`.** The nightly job needs every published trip and the interface only has per-user and per-slug finders.
3. **`weather/CannedHttp` (test code) is made public** so the flights tests reuse it instead of copying it.

## Global Constraints

- TTL tiers for AeroDataBox data: more than 3 days **72 h**, within 3 days **24 h**, within 24 h **1 h**, after landing never refetched. All configurable.
- AviationStack extras: TTL **15 minutes**, only from **2 h before departure until landed**.
- **Landed** = AeroDataBox status `Arrived`, `Landed` or `Canceled`, or scheduled arrival plus **3 hours** has passed.
- Monthly limits: AeroDataBox **400**, AviationStack **100**; each capped at **90%** (360 and 90). Limits and cap percent configurable.
- The counter is keyed by service and UTC calendar month. **No monthly reset job.**
- The quota check and increment are one operation, `ApiUsage.tryAcquire(service, cap)`; a call counts when attempted.
- Nightly job: cron `app.flights.prewarm.cron`, env `FLIGHTS_PREWARM_CRON`, default `0 0 1 * * *`, zone UTC; published trips only; flights within 72 h; deduped; skip fresh records; soonest first; stop at the AeroDataBox cap; AeroDataBox only.
- `flight` is allowed only on the `TRANSPORTATION` category (data key `transport`); a non-transport entry is refused with a 400; changing an entry's category away from transport clears it.
- Statuses of a lookup: `found`, `notFound` (the service answered empty), `unavailable` (no key, cap, timeout, error). Only a genuine empty answer is cached as a miss.
- Times are filled as airport wall-clock time from AeroDataBox's `local` value, never `utc`. AviationStack's scheduled and estimated times are never used (it labels local time as UTC).
- Airport data comes from AeroDataBox only. The snapshot holds `iata, icao, name, city, countryCode, timezone, lat, lon`, plus terminals.
- Keys never reach the frontend or a published page. Coordinates are not shipped on a published page.
- New settings live in their own `@ConfigurationProperties` record (`FlightProperties`), never in `AppProperties`.
- Migration is `V13__flight_lookup.sql`, schema only. Tables: `flight_records`, `codeshare_mappings`, `api_usage`. Column: `itinerary_items.flight jsonb` (nullable).
- YAML files: `data/api-usage.yml`, `data/flights/records.yml`, `data/flights/codeshares.yml`.
- Every word a member reads goes through message files (`messages_en.properties`, `js/i18n/en.js`, `page.*` keys). `npm run check` and `MessageKeysTest` must pass.
- An entry with a date but no time is all-day; the form's existing `allDay: !startTime` logic is unchanged.
- The published page never fetches on load; a click fetches; the browser caches the reply for the TTL the server sends.

## Review Focus

Failure modes the spec implies but no obvious test covers; each has a test in the named task.

1. **A flight typed as `kl 2842` or `kl2842`** must behave exactly like `KL2842` in the lookup, the cache key and the mapping (Task 4, Task 9).
2. **An overnight or date-line flight** whose arrival local date differs from its departure date must fill the *arrival's* date into the end date (Task 9).
3. **AviationStack returns rows but none is a codeshare** (a plain flight AeroDataBox simply lacks) must be `notFound`, not a crash or a bogus mapping (Task 7).
4. **Two callers race for the last call under the cap** must let exactly one through (Task 1).
5. **An entry whose date was edited after publishing** makes the public refresh a 404, and the page must stay quiet, not show an error (Task 10, Task 12).
6. **A timeout or 5xx during a lookup** must never be cached as "not found" (Task 8).

## File Structure

```
planner-api/src/main/java/com/josephinealinea/planner/
  usage/
    domain/ApiUsageRow.java
    infra/ApiUsageRepository.java, YamlApiUsageRepository.java, JdbcApiUsageRepository.java
    api/ApiUsageService.java
  flights/
    Fetch.java, FlightNumbers.java, FlightProperties.java, FlightsConfig.java
    AeroDataBoxClient.java, AviationStackClient.java
    domain/Airport.java, FlightSnapshot.java, FlightSchedule.java, FlightLive.java,
           FlightRecord.java, CodeshareMapping.java
    infra/FlightRecordRepository.java, YamlFlightRecordRepository.java, JdbcFlightRecordRepository.java,
          CodeshareRepository.java, YamlCodeshareRepository.java, JdbcCodeshareRepository.java
    api/FlightFreshness.java, FlightData.java, FlightLookup.java,
        FlightStatusService.java, FlightPrewarmJob.java
    web/FlightController.java, PublicFlightController.java
  itinerary/domain/ItineraryItem.java          (modify: flight field)
  itinerary/api/ItineraryService.java          (modify: Input, rules)
  itinerary/web/ItineraryController.java       (modify: requests)
  itinerary/infra/JdbcItineraryRepository.java (modify: jsonb column)
  storage/YamlPaths.java                       (modify: three paths)
  trips/infra/TripRepository.java + both impls (modify: findAllPublished)
  publish/api/PublishedTrip.java, StaticSiteRenderer.java (modify)
  config/SecurityConfig.java                   (modify: public GET)
planner-api/src/main/resources/
  db/migration/V13__flight_lookup.sql
  application.yml, messages_en.properties
  publish/page.js, page.css
planner-web/
  js/pages/trip/flight-lookup.js (new), checklist.js, itinerary.js, js/api.js, js/i18n/en.js
  trip.html, scss/components/_flight.scss (new)
docs/external-apis/aerodatabox.md, aviationstack.md, README.md; docs/scheduled/flights-prewarm.md
```

---

### Task 1: Schema V13 and the shared API usage counter

**Files:**
- Create: `planner-api/src/main/resources/db/migration/V13__flight_lookup.sql`
- Create: `usage/domain/ApiUsageRow.java`, `usage/infra/ApiUsageRepository.java`, `usage/infra/YamlApiUsageRepository.java`, `usage/infra/JdbcApiUsageRepository.java`, `usage/api/ApiUsageService.java`
- Modify: `storage/YamlPaths.java`
- Test: `src/test/java/com/josephinealinea/planner/usage/infra/ApiUsageRepositoryContract.java`, `YamlApiUsageRepositoryContractTest.java`, `JdbcApiUsageRepositoryContractTest.java`, `src/test/java/com/josephinealinea/planner/usage/api/ApiUsageServiceTest.java`

(Paths below are relative to `planner-api/src/main/java/com/josephinealinea/planner/` and `planner-api/src/test/java/com/josephinealinea/planner/`.)

**Interfaces:**
- Produces: `ApiUsageRepository.tryAcquire(String service, String month, int cap): boolean`; `ApiUsageRepository.calls(String service, String month): int`; `ApiUsageService.tryAcquire(String service, int cap): boolean`; `ApiUsageService.calls(String service): int`; `YamlPaths.apiUsage()`, `flightRecords()`, `flightCodeshares()`.

- [ ] **Step 1: Write the migration**

```sql
-- Flight lookup: one document on the entry, three install-wide tables.
-- Schema only. None of the three tables references trips: they hold public
-- schedule facts and a call counter, not trip data, so deleting a trip leaves
-- them alone.

ALTER TABLE itinerary_items ADD COLUMN flight jsonb;

CREATE TABLE flight_records (
    flight_number       text        NOT NULL,
    departure_date      date        NOT NULL,
    schedule            jsonb,
    live                jsonb,
    schedule_fetched_at timestamptz,
    live_fetched_at     timestamptz,
    not_found           boolean     NOT NULL DEFAULT false,
    PRIMARY KEY (flight_number, departure_date)
);

CREATE TABLE codeshare_mappings (
    booked_number    text        PRIMARY KEY,
    operating_number text        NOT NULL,
    resolved_at      timestamptz NOT NULL
);

CREATE TABLE api_usage (
    service text    NOT NULL,
    month   text    NOT NULL,
    calls   integer NOT NULL DEFAULT 0,
    PRIMARY KEY (service, month)
);
```

- [ ] **Step 2: Add the three paths to `YamlPaths`** (next to `rates()`):

```java
    /** Global, shared by every API the install counts: calls per service per month. */
    public Path apiUsage() { return root.resolve("api-usage.yml"); }

    /** Global flight caches: not per trip, they hold public schedule facts. */
    public Path flightRecords() { return root.resolve("flights").resolve("records.yml"); }

    public Path flightCodeshares() { return root.resolve("flights").resolve("codeshares.yml"); }
```

- [ ] **Step 3: Write the failing contract test** `usage/infra/ApiUsageRepositoryContract.java`

Each test uses a unique service name so it does not depend on what earlier tests left in a shared Postgres.

```java
package com.josephinealinea.planner.usage.infra;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** What any {@link ApiUsageRepository} must do, run against YAML and PostgreSQL. */
public abstract class ApiUsageRepositoryContract {

    protected abstract ApiUsageRepository repository();

    private static String service() {
        return "svc-" + UUID.randomUUID();
    }

    @Test
    void callsAreAcquiredUpToTheCapAndNoFurther() {
        String service = service();

        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isFalse();

        assertThat(repository().calls(service, "2026-10")).isEqualTo(3);
    }

    @Test
    void aNewMonthStartsAtZeroWithNothingToReset() {
        String service = service();
        repository().tryAcquire(service, "2026-10", 1);
        assertThat(repository().tryAcquire(service, "2026-10", 1)).isFalse();

        assertThat(repository().tryAcquire(service, "2026-11", 1)).isTrue();
        assertThat(repository().calls(service, "2026-10")).as("history is kept").isEqualTo(1);
    }

    @Test
    void servicesAreCountedSeparately() {
        String a = service();
        String b = service();
        repository().tryAcquire(a, "2026-10", 1);

        assertThat(repository().tryAcquire(b, "2026-10", 1)).isTrue();
        assertThat(repository().calls(a, "2026-10")).isEqualTo(1);
    }

    @Test
    void aCapOfZeroAcquiresNothingAndWritesNothing() {
        String service = service();

        assertThat(repository().tryAcquire(service, "2026-10", 0)).isFalse();
        assertThat(repository().calls(service, "2026-10")).isZero();
    }

    @Test
    void anUnusedServiceHasZeroCalls() {
        assertThat(repository().calls(service(), "2026-10")).isZero();
    }

    /** Review focus 4: with one call left, exactly one of many racing callers gets it. */
    @Test
    void racingCallersCannotOvershootTheCap() throws Exception {
        String service = service();
        int cap = 5;
        int callers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<Boolean> call = () -> {
                go.await();
                return repository().tryAcquire(service, "2026-10", cap);
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        int granted = 0;
        for (Future<Boolean> result : results) if (result.get()) granted++;
        pool.shutdown();

        assertThat(granted).isEqualTo(cap);
        assertThat(repository().calls(service, "2026-10")).isEqualTo(cap);
    }
}
```

- [ ] **Step 4: Write the two subclasses**

`YamlApiUsageRepositoryContractTest.java`:

```java
package com.josephinealinea.planner.usage.infra;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The usage contract against {@code api-usage.yml} in a temp directory. Always runs. */
class YamlApiUsageRepositoryContractTest extends ApiUsageRepositoryContract {

    private YamlApiUsageRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        repository = new YamlApiUsageRepository(new YamlStore(), new YamlPaths(props));
    }

    @Override
    protected ApiUsageRepository repository() {
        return repository;
    }
}
```

`JdbcApiUsageRepositoryContractTest.java`:

```java
package com.josephinealinea.planner.usage.infra;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

/** The usage contract against PostgreSQL: {@code api_usage}. */
@PostgresTest
class JdbcApiUsageRepositoryContractTest extends ApiUsageRepositoryContract {

    private final JdbcApiUsageRepository repository = new JdbcApiUsageRepository(PostgresTestDatabase.jdbc());

    @Override
    protected ApiUsageRepository repository() {
        return repository;
    }
}
```

- [ ] **Step 5: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'YamlApiUsageRepositoryContractTest'`
Expected: FAIL (compilation: `ApiUsageRepository` not found).

- [ ] **Step 6: Write the production code**

`usage/domain/ApiUsageRow.java`:

```java
package com.josephinealinea.planner.usage.domain;

/**
 * One counter: how many calls a service has made in a UTC calendar month.
 * A new month is a new row, so nothing ever has to be reset.
 */
public record ApiUsageRow(String service, String month, int calls) {}
```

`usage/infra/ApiUsageRepository.java`:

```java
package com.josephinealinea.planner.usage.infra;

/**
 * Calls per external service per month: {@link YamlApiUsageRepository} with the
 * database flag off, {@link JdbcApiUsageRepository} with it on.
 *
 * Not trip-scoped, and not specific to flights: another API is counted by
 * naming another service.
 */
public interface ApiUsageRepository {

    /**
     * Records one call if fewer than {@code cap} have been made in
     * {@code month}. True means the call may go out. Checking and counting are
     * one operation so two callers cannot both take the last call.
     */
    boolean tryAcquire(String service, String month, int cap);

    /** Calls made so far; zero for a service or month never seen. */
    int calls(String service, String month);
}
```

`usage/infra/YamlApiUsageRepository.java`:

```java
package com.josephinealinea.planner.usage.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.usage.domain.ApiUsageRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * One file for the install, {@code data/api-usage.yml}: a list of
 * {@link ApiUsageRow}. {@code synchronized} is the lock, which is enough because
 * YAML mode is single-instance only, like every YAML store here.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlApiUsageRepository implements ApiUsageRepository {

    private static final TypeReference<List<ApiUsageRow>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlApiUsageRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized boolean tryAcquire(String service, String month, int cap) {
        if (cap <= 0) return false;
        List<ApiUsageRow> rows = store.readList(paths.apiUsage(), ROWS);
        for (int i = 0; i < rows.size(); i++) {
            ApiUsageRow row = rows.get(i);
            if (row.service().equals(service) && row.month().equals(month)) {
                if (row.calls() >= cap) return false;
                rows.set(i, new ApiUsageRow(service, month, row.calls() + 1));
                store.write(paths.apiUsage(), rows);
                return true;
            }
        }
        rows.add(new ApiUsageRow(service, month, 1));
        store.write(paths.apiUsage(), rows);
        return true;
    }

    @Override
    public synchronized int calls(String service, String month) {
        return store.readList(paths.apiUsage(), ROWS).stream()
                .filter(row -> row.service().equals(service) && row.month().equals(month))
                .mapToInt(ApiUsageRow::calls)
                .findFirst()
                .orElse(0);
    }
}
```

`usage/infra/JdbcApiUsageRepository.java`:

```java
package com.josephinealinea.planner.usage.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code api_usage}. The check and the increment are one statement: the
 * {@code WHERE} on the conflict branch stops the update once the cap is reached,
 * and the row count says whether the call was granted, so two racing callers
 * cannot both take the last one.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcApiUsageRepository implements ApiUsageRepository {

    private final JdbcClient jdbc;

    public JdbcApiUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(String service, String month, int cap) {
        if (cap <= 0) return false;
        return jdbc.sql("""
                        INSERT INTO api_usage (service, month, calls) VALUES (:service, :month, 1)
                        ON CONFLICT (service, month) DO UPDATE SET calls = api_usage.calls + 1
                        WHERE api_usage.calls < :cap
                        """)
                .param("service", service)
                .param("month", month)
                .param("cap", cap)
                .update() == 1;
    }

    @Override
    public int calls(String service, String month) {
        return jdbc.sql("SELECT calls FROM api_usage WHERE service = :service AND month = :month")
                .param("service", service)
                .param("month", month)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
```

`usage/api/ApiUsageService.java`:

```java
package com.josephinealinea.planner.usage.api;

import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Counts outbound calls against a monthly cap. The month is the UTC calendar
 * month, so a new month is simply a new key and no job has to reset anything.
 */
@Service
public class ApiUsageService {

    private final ApiUsageRepository repository;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public ApiUsageService(ApiUsageRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ApiUsageService(ApiUsageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** True when the call may go out; it is counted when granted, even if it then fails. */
    public boolean tryAcquire(String service, int cap) {
        return repository.tryAcquire(service, monthOf(clock.instant()), cap);
    }

    public int calls(String service) {
        return repository.calls(service, monthOf(clock.instant()));
    }

    static String monthOf(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return "%04d-%02d".formatted(utc.getYear(), utc.getMonthValue());
    }
}
```

- [ ] **Step 7: Write `ApiUsageServiceTest`** (`usage/api/ApiUsageServiceTest.java`)

```java
package com.josephinealinea.planner.usage.api;

import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiUsageServiceTest {

    /** In-memory stand-in: the month arrives as part of the key. */
    private static final class Memory implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service + "|" + month, 0);
            if (now >= cap) return false;
            counts.put(service + "|" + month, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service + "|" + month, 0);
        }
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void monthIsTheUtcCalendarMonth() {
        assertThat(ApiUsageService.monthOf(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo("2026-10");
        assertThat(ApiUsageService.monthOf(Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo("2026-11");
    }

    @Test
    void aNewMonthNeedsNoResetJob() {
        Memory memory = new Memory();
        assertThat(new ApiUsageService(memory, at("2026-10-31T23:59:00Z")).tryAcquire("aerodatabox", 1)).isTrue();
        assertThat(new ApiUsageService(memory, at("2026-10-31T23:59:30Z")).tryAcquire("aerodatabox", 1)).isFalse();

        ApiUsageService november = new ApiUsageService(memory, at("2026-11-01T00:00:01Z"));
        assertThat(november.tryAcquire("aerodatabox", 1)).isTrue();
        assertThat(november.calls("aerodatabox")).isEqualTo(1);
    }
}
```

- [ ] **Step 8: Run all usage tests**

Run: `cd planner-api && ./gradlew test --tests '*ApiUsage*'`
Expected: PASS. Then read `planner-api/build/test-results/test/*.xml` for `JdbcApiUsageRepositoryContractTest` and confirm `skipped="0"` (Docker/Colima must be up, see CLAUDE.md).

- [ ] **Step 9: Checkpoint** — the usage tests are green on both stores; `MigrationsAreSchemaOnlyTest` still passes: `./gradlew test --tests 'MigrationsAreSchemaOnlyTest'`.

---

### Task 2: Flight domain records and the `flight` field on the entry

**Files:**
- Create: `flights/domain/Airport.java`, `FlightSnapshot.java`, `FlightSchedule.java`, `FlightLive.java`, `FlightRecord.java`, `CodeshareMapping.java`
- Modify: `itinerary/domain/ItineraryItem.java`, `itinerary/infra/JdbcItineraryRepository.java`
- Test: `src/test/java/com/josephinealinea/planner/itinerary/ItineraryRepositoryContract.java` (modify `fullyPopulated()`)

**Interfaces:**
- Produces (all Jackson-friendly records, times are ISO strings so both the YAML mapper and the plain JSON mapper in `JdbcValues` read them):
  - `Airport(String iata, String icao, String name, String city, String countryCode, String timezone, Double lat, Double lon)`
  - `FlightSnapshot(String number, String operatingNumber, Airport from, Airport to, String terminalFrom, String terminalTo)`
  - `FlightSchedule(String number, String status, String aircraftModel, String airline, Leg departure, Leg arrival, String lastUpdatedUtc)` with `Leg(Airport airport, String scheduledLocal, String scheduledUtc, String revisedLocal, String predictedLocal, String terminal)`
  - `FlightLive(String status, Leg departure, Leg arrival)` with `Leg(String gate, String baggageBelt, Integer delayMinutes, String actualLocal)`
  - `FlightRecord(String flightNumber, LocalDate departureDate, FlightSchedule schedule, FlightLive live, Instant scheduleFetchedAt, Instant liveFetchedAt, boolean notFound)` with `withSchedule(FlightSchedule, Instant)`, `withLive(FlightLive, Instant)`, `static FlightRecord missing(String, LocalDate, Instant)`
  - `CodeshareMapping(String bookedNumber, String operatingNumber, Instant resolvedAt)`
  - `ItineraryItem.getFlight(): FlightSnapshot`, `setFlight(FlightSnapshot)`

- [ ] **Step 1: Write the domain records** (each in `flights/domain/`, package `com.josephinealinea.planner.flights.domain`)

```java
// Airport.java
package com.josephinealinea.planner.flights.domain;

/** An airport as AeroDataBox describes it. Coordinates and country are kept because refetching every saved flight later would cost quota. */
public record Airport(String iata, String icao, String name, String city, String countryCode,
                      String timezone, Double lat, Double lon) {}
```

```java
// FlightSnapshot.java
package com.josephinealinea.planner.flights.domain;

/**
 * What an itinerary entry keeps about its flight: only facts that do not change
 * day to day. Gate, baggage and delay are deliberately not here; they live in
 * the cache with a fetch time. A snapshot holding only {@code number} is valid:
 * a member who never uses the lookup still gets it on the plan card.
 */
public record FlightSnapshot(String number, String operatingNumber, Airport from, Airport to,
                             String terminalFrom, String terminalTo) {}
```

```java
// FlightSchedule.java
package com.josephinealinea.planner.flights.domain;

/** AeroDataBox's answer for one flight on one date. Times are ISO-8601 strings with offsets. */
public record FlightSchedule(String number, String status, String aircraftModel, String airline,
                             Leg departure, Leg arrival, String lastUpdatedUtc) {

    public record Leg(Airport airport, String scheduledLocal, String scheduledUtc,
                      String revisedLocal, String predictedLocal, String terminal) {}
}
```

```java
// FlightLive.java
package com.josephinealinea.planner.flights.domain;

/**
 * AviationStack's extras: gate, baggage belt, delay and actual times. Actual
 * times are airport wall-clock (AviationStack labels them UTC; the offset is
 * dropped on the way in). A real 0 minute delay is an answer, so it is Integer.
 */
public record FlightLive(String status, Leg departure, Leg arrival) {

    public record Leg(String gate, String baggageBelt, Integer delayMinutes, String actualLocal) {}
}
```

```java
// FlightRecord.java
package com.josephinealinea.planner.flights.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the install-wide flight cache, keyed by operating flight number
 * and the local departure date. The two halves are fetched separately, so each
 * has its own fetch time.
 */
public record FlightRecord(String flightNumber, LocalDate departureDate,
                           FlightSchedule schedule, FlightLive live,
                           Instant scheduleFetchedAt, Instant liveFetchedAt,
                           boolean notFound) {

    /** A stored genuine empty answer. It expires by the negative TTL. */
    public static FlightRecord missing(String flightNumber, LocalDate date, Instant at) {
        return new FlightRecord(flightNumber, date, null, null, at, null, true);
    }

    public FlightRecord withSchedule(FlightSchedule schedule, Instant at) {
        return new FlightRecord(flightNumber, departureDate, schedule, live, at, liveFetchedAt, false);
    }

    public FlightRecord withLive(FlightLive live, Instant at) {
        return new FlightRecord(flightNumber, departureDate, schedule, live, scheduleFetchedAt, at, notFound);
    }
}
```

```java
// CodeshareMapping.java
package com.josephinealinea.planner.flights.domain;

import java.time.Instant;

/** A codeshare pairing (KL2842 to BT857). Permanent: it is a fact about the schedule. */
public record CodeshareMapping(String bookedNumber, String operatingNumber, Instant resolvedAt) {}
```

- [ ] **Step 2: Add the field to `ItineraryItem`** (after `travellerIds`; add `import com.josephinealinea.planner.flights.domain.FlightSnapshot;`)

```java
    /**
     * The flight a transport entry is about, when the member gave one. Absent
     * on every other entry. Only the plan's own row carries it, like the cost;
     * the later days of a stay never do. See FlightSnapshot.
     */
    private FlightSnapshot flight;
```

and with the other accessors:

```java
    public FlightSnapshot getFlight() { return flight; }
    public void setFlight(FlightSnapshot flight) { this.flight = flight; }
```

- [ ] **Step 3: Extend the failing round-trip fixture.** In `ItineraryRepositoryContract`, find `fullyPopulated()` (`grep -n "fullyPopulated" src/test/java/com/josephinealinea/planner/itinerary/ItineraryRepositoryContract.java`) and, before its `return`, add (with imports `com.josephinealinea.planner.flights.domain.Airport` and `FlightSnapshot`):

```java
        item.setFlight(new FlightSnapshot("KL2842", "BT857",
                new Airport("TLL", "EETN", "Tallinn Lennart Meri", "Tallinn", "EE", "Europe/Tallinn", 59.4133, 24.8328),
                new Airport("AMS", "EHAM", "Amsterdam Schiphol", "Amsterdam", "NL", "Europe/Amsterdam", 52.3086, 4.7639),
                "1", "2"));
```

(Use the local variable name `fullyPopulated()` already uses for the entry; if it is not `item`, use that name.)

- [ ] **Step 4: Run to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'YamlItineraryRepositoryTest' --tests 'JdbcItineraryRepositoryTest'`
Expected: the Postgres one FAILS (`everyFieldComesBackAsItWasSaved`: flight not stored). YAML already passes because Jackson writes the field.

- [ ] **Step 5: Map the column in `JdbcItineraryRepository`.** Add `"flight"` to the column list, pass `Set.of("flight")`, and map both ways:

```java
        super(jdbc, transactionManager, "itinerary_items",
                List.of("checklist_item_id", "plan_id", "category", "description", "note",
                        "start_at", "end_at", "all_day", "cost", "currency", "budget_item_id",
                        "country_codes", "sort_order",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id", "traveller_ids",
                        "flight"),
                Set.of("flight"),
                ItineraryItem::getId);
```

```java
        values.put("flight", JdbcValues.json(item.getFlight()));
```

```java
        item.setFlight(JdbcValues.json(rs, "flight", FlightSnapshot.class));
```

(Add `import java.util.Set;` and `import com.josephinealinea.planner.flights.domain.FlightSnapshot;`.)

- [ ] **Step 6: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'YamlItineraryRepositoryTest' --tests 'JdbcItineraryRepositoryTest'`
Expected: PASS; `skipped="0"` for the Jdbc class in `build/test-results/test/`.

- [ ] **Step 7: Checkpoint** — `./gradlew test --tests '*Itinerary*'` is green.

---

### Task 3: Service rules and the request shape for `flight`

**Files:**
- Modify: `itinerary/api/ItineraryService.java`, `itinerary/web/ItineraryController.java`
- Modify: `flights/FlightNumbers.java` is created in Task 4, so this task creates it here instead.
- Create: `flights/FlightNumbers.java`
- Modify: `planner-api/src/main/resources/messages_en.properties`
- Test: `src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceFlightTest.java`

**Interfaces:**
- Consumes: `FlightSnapshot`, `ItineraryItem.setFlight` (Task 2).
- Produces: `FlightNumbers.normalise(String): String`, `FlightNumbers.valid(String): boolean`; `ItineraryService.Input` gains a trailing `FlightSnapshot flight`, with the old 15-argument shape kept as a constructor so the 39 existing call sites compile unchanged.

- [ ] **Step 1: Create `FlightNumbers`**

```java
package com.josephinealinea.planner.flights;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One spelling of a flight number everywhere it is compared or stored: no
 * spaces, upper case. {@code kl 2842}, {@code KL 2842} and {@code KL2842} are
 * the same flight, and a cache keyed on the raw text would treat them as three.
 */
public final class FlightNumbers {

    // Two-character airline designator (IATA letters and digits), 1-4 digits, an optional letter suffix.
    private static final Pattern SHAPE = Pattern.compile("^[A-Z0-9]{2}\\d{1,4}[A-Z]?$");

    private FlightNumbers() {}

    /** Null stays null. */
    public static String normalise(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean valid(String normalised) {
        return normalised != null && SHAPE.matcher(normalised).matches();
    }
}
```

- [ ] **Step 2: Add message keys** to `messages_en.properties` (find the `error.itinerary.` block with `grep -n "error.itinerary" src/main/resources/messages_en.properties` and add beside it):

```properties
error.flight.transportOnly=A flight number can only be added to a transport entry.
error.flight.numberInvalid=That does not look like a flight number, for example KL2842.
error.flight.notFound=That flight is not on a published trip.
```

- [ ] **Step 3: Write the failing test** `itinerary/ItineraryServiceFlightTest.java`. First look at how `ItineraryServiceCountryLinksTest` builds an `ItineraryService` and a trip (`sed -n 1,80p src/test/java/com/josephinealinea/planner/itinerary/ItineraryServiceCountryLinksTest.java`) and reuse its fixture helpers verbatim; the assertions below are the contract:

```java
    @Test
    void aFlightIsAcceptedOnATransportEntryAndNormalised() {
        ItineraryItem plan = service.create(trip.getId(), owner, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("kl 2842", "bt857", null, null, null, null)));

        assertThat(plan.getFlight().number()).isEqualTo("KL2842");
        assertThat(plan.getFlight().operatingNumber()).isEqualTo("BT857");
    }

    @Test
    void aFlightOnANonTransportEntryIsRefused() {
        assertThatThrownBy(() -> service.create(trip.getId(), owner, inputWithFlight(
                ChecklistCategory.FOOD, new FlightSnapshot("KL2842", null, null, null, null, null))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("error.flight.transportOnly");
    }

    @Test
    void aBadNumberIsRefused() {
        assertThatThrownBy(() -> service.create(trip.getId(), owner, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("hello", null, null, null, null, null))))
                .hasMessageContaining("error.flight.numberInvalid");
    }

    @Test
    void absentLeavesTheFlightAndAnEmptyNumberClearsIt() {
        ItineraryItem plan = service.create(trip.getId(), owner, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", "BT857", null, null, null, null)));

        service.update(trip.getId(), owner, plan.getId(), patchWithFlight(null));
        assertThat(service.require(trip, plan.getId()).getFlight()).isNotNull();

        service.update(trip.getId(), owner, plan.getId(),
                patchWithFlight(new FlightSnapshot("", null, null, null, null, null)));
        assertThat(service.require(trip, plan.getId()).getFlight()).isNull();
    }

    @Test
    void movingAnEntryOffTransportClearsItsFlight() {
        ItineraryItem plan = service.create(trip.getId(), owner, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", null, null, null, null, null)));

        service.update(trip.getId(), owner, plan.getId(), patchWithCategory(ChecklistCategory.FOOD));

        assertThat(service.require(trip, plan.getId()).getFlight()).isNull();
    }
```

with helpers `inputWithFlight(category, flight)` and `patchWithFlight(flight)` / `patchWithCategory(category)` building an `ItineraryService.Input` using the new 16-argument canonical constructor (all other components `null`, description `"KLM flight"` for creates).

- [ ] **Step 4: Run to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceFlightTest'`
Expected: FAIL (compile: `Input` has no flight component).

- [ ] **Step 5: Extend `ItineraryService.Input`.** Add the trailing component after `String note` and keep the 15-argument shape:

```java
                        String note,
                        /**
                         * The flight, for a transport entry. Null leaves it alone; a
                         * number that is empty clears the whole document.
                         */
                        FlightSnapshot flight) {

        /** The shape from before flights existed: says nothing about them. */
        public Input(String checklistItemId, ChecklistCategory category, String description,
                     LocalDateTime startAt, LocalDateTime endAt, Boolean allDay, BigDecimal cost,
                     String currency, Boolean costCharged, List<String> costSharedByUserIds,
                     String costPaidByUserId, List<String> countryCodes,
                     List<String> travellerIds, Boolean inheritTravellers, String note) {
            this(checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note, null);
        }
```

(Leave the two older secondary constructors as they are: they call `this(… 15 args)` which now resolves to the shape above.) Add `import com.josephinealinea.planner.flights.domain.FlightSnapshot;` and `import com.josephinealinea.planner.flights.FlightNumbers;`.

- [ ] **Step 6: Add the rule** as one private method and call it from both paths.

```java
    /**
     * The flight rule, in one place: transport only, well-formed, one spelling.
     * Returns what should be stored: null for "nothing", the same instance when
     * there is nothing to change.
     */
    private static FlightSnapshot checkedFlight(ChecklistCategory category, FlightSnapshot wanted) {
        String number = FlightNumbers.normalise(wanted.number());
        if (number == null || number.isEmpty()) return null;
        if (category != ChecklistCategory.TRANSPORTATION) {
            throw ApiException.badRequest("error.flight.transportOnly");
        }
        if (!FlightNumbers.valid(number)) {
            throw ApiException.badRequest("error.flight.numberInvalid");
        }
        String operating = FlightNumbers.normalise(wanted.operatingNumber());
        if (operating != null && operating.isEmpty()) operating = null;
        return new FlightSnapshot(number, operating, wanted.from(), wanted.to(),
                wanted.terminalFrom(), wanted.terminalTo());
    }
```

In `create`, after `plan.setNote(...)`:

```java
        if (input.flight() != null) plan.setFlight(checkedFlight(plan.getCategory(), input.flight()));
```

In `update`, after `if (input.category() != null) plan.setCategory(input.category());`:

```java
        if (input.flight() != null) {
            // An empty number clears it; a value sets it. Absent leaves it alone.
            plan.setFlight(checkedFlight(plan.getCategory(), input.flight()));
        } else if (plan.getFlight() != null && plan.getCategory() != ChecklistCategory.TRANSPORTATION) {
            // Moved away from transport: a flight on a meal would be nonsense.
            plan.setFlight(null);
        }
```

- [ ] **Step 7: Add the field to both request records** in `ItineraryController` (trailing component, and pass it):

```java
            String note,
            /** The flight for a transport entry. Absent leaves it; an empty number clears it. */
            FlightSnapshot flight) {
```

and change both `toInput()` bodies to end with `travellerIds, inheritTravellers, note, flight);` (import `FlightSnapshot`).

- [ ] **Step 8: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'ItineraryServiceFlightTest' --tests '*Itinerary*' --tests 'MessageKeysTest'`
Expected: PASS. (`MessageKeysTest` proves the new keys exist.)

- [ ] **Step 9: Add a stay-spread guard.** In the same test class add `stayNightsCarryNoFlight`: create a `LODGING` plan with two nights and assert every entry from `itinerary.findAll(...)` has `getFlight() == null` (the spread code copies fields explicitly and never copies `flight`; this pins it). Run and expect PASS.

- [ ] **Step 10: Checkpoint** — `./gradlew test` for the whole API is green (the 39 call sites of `Input` still compile).

---

### Task 4: `FlightProperties`, the fetch result and HTTP wiring

**Files:**
- Create: `flights/Fetch.java`, `flights/FlightProperties.java`, `flights/FlightsConfig.java`
- Modify: `planner-api/src/main/resources/application.yml`
- Modify (test): `src/test/java/com/josephinealinea/planner/weather/CannedHttp.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/FlightPropertiesTest.java`, `FlightNumbersTest.java`

**Interfaces:**
- Produces:
  - `Fetch<T>(Status status, T value)` with `enum Status { FOUND, NOT_FOUND, UNAVAILABLE }` and static `found(T)`, `notFound()`, `unavailable()`
  - `FlightProperties(Service aerodatabox, Service aviationstack, Ttl ttl, Live live, Duration negativeTtl, Prewarm prewarm)`; `Service(String baseUrl, String key, int monthlyLimit, int capPercent, Duration timeout)` with `cap()` and `enabled()`; `Ttl(Duration farAhead, Duration withinThreeDays, Duration withinOneDay)`; `Live(Duration window, Duration ttl)`; `Prewarm(String cron)`
  - beans `RestClient aeroDataBoxHttp`, `RestClient aviationStackHttp` (named so they cannot clash with the `@Component` client classes, see CLAUDE.md Traps)

- [ ] **Step 1: Make `CannedHttp` reusable.** In `src/test/java/com/josephinealinea/planner/weather/CannedHttp.java` change `final class CannedHttp` to `public final class CannedHttp` and add `public` to `ok`, `status`, `asked`, `callCount` and `client`. Run `./gradlew test --tests 'WeatherClientTest'` and expect PASS.

- [ ] **Step 2: Write the failing tests**

`FlightNumbersTest`:

```java
package com.josephinealinea.planner.flights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightNumbersTest {

    /** Review focus 1. */
    @Test
    void oneFlightHasOneSpelling() {
        assertThat(FlightNumbers.normalise("kl 2842")).isEqualTo("KL2842");
        assertThat(FlightNumbers.normalise(" KL2842 ")).isEqualTo("KL2842");
        assertThat(FlightNumbers.normalise("bt857")).isEqualTo("BT857");
        assertThat(FlightNumbers.normalise(null)).isNull();
    }

    @Test
    void shapeIsAirlineCodeDigitsAndAnOptionalLetter() {
        assertThat(FlightNumbers.valid("KL2842")).isTrue();
        assertThat(FlightNumbers.valid("W61968")).isTrue();
        assertThat(FlightNumbers.valid("BA117")).isTrue();
        assertThat(FlightNumbers.valid("AB1234C")).isTrue();
        assertThat(FlightNumbers.valid("HELLO")).isFalse();
        assertThat(FlightNumbers.valid("K")).isFalse();
        assertThat(FlightNumbers.valid("KL")).isFalse();
        assertThat(FlightNumbers.valid("KL12345")).isFalse();
        assertThat(FlightNumbers.valid("")).isFalse();
        assertThat(FlightNumbers.valid(null)).isFalse();
    }
}
```

`FlightPropertiesTest`:

```java
package com.josephinealinea.planner.flights;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FlightPropertiesTest {

    @Test
    void defaultsMatchTheAgreedTiersAndQuotas() {
        FlightProperties props = new FlightProperties(null, null, null, null, null, null);

        assertThat(props.aerodatabox().monthlyLimit()).isEqualTo(400);
        assertThat(props.aerodatabox().cap()).isEqualTo(360);
        assertThat(props.aviationstack().monthlyLimit()).isEqualTo(100);
        assertThat(props.aviationstack().cap()).isEqualTo(90);
        assertThat(props.ttl().farAhead()).isEqualTo(Duration.ofHours(72));
        assertThat(props.ttl().withinThreeDays()).isEqualTo(Duration.ofHours(24));
        assertThat(props.ttl().withinOneDay()).isEqualTo(Duration.ofHours(1));
        assertThat(props.live().window()).isEqualTo(Duration.ofHours(2));
        assertThat(props.live().ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.prewarm().cron()).isEqualTo("0 0 1 * * *");
    }

    @Test
    void aServiceWithNoKeyIsDisabledNotBroken() {
        FlightProperties props = new FlightProperties(null, null, null, null, null, null);

        assertThat(props.aerodatabox().enabled()).isFalse();
        assertThat(new FlightProperties.Service("http://x", "k", 10, 50, null).cap()).isEqualTo(5);
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'FlightNumbersTest' --tests 'FlightPropertiesTest'`
Expected: `FlightNumbersTest` PASSES (class exists from Task 3), `FlightPropertiesTest` FAILS to compile.

- [ ] **Step 4: Write `Fetch`**

```java
package com.josephinealinea.planner.flights;

/**
 * What asking an outside service came to. Three outcomes, never conflated:
 * {@code NOT_FOUND} is the service answering that it has no such flight (worth
 * remembering briefly); {@code UNAVAILABLE} is everything else that stopped an
 * answer (no key, cap reached, timeout, error) and must never be remembered as
 * a miss, or an outage would poison the cache.
 */
public record Fetch<T>(Status status, T value) {

    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public static <T> Fetch<T> found(T value) {
        return new Fetch<>(Status.FOUND, value);
    }

    public static <T> Fetch<T> notFound() {
        return new Fetch<>(Status.NOT_FOUND, null);
    }

    public static <T> Fetch<T> unavailable() {
        return new Fetch<>(Status.UNAVAILABLE, null);
    }
}
```

- [ ] **Step 5: Write `FlightProperties`**

```java
package com.josephinealinea.planner.flights;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Everything the flights module can be told, in a record of its own rather than
 * a component of AppProperties, which a score of tests construct positionally.
 * A missing key means that service is off, not that the app fails to start.
 */
@ConfigurationProperties(prefix = "app.flights")
public record FlightProperties(Service aerodatabox, Service aviationstack, Ttl ttl,
                               Live live, Duration negativeTtl, Prewarm prewarm) {

    public FlightProperties {
        if (aerodatabox == null) aerodatabox = new Service("https://aerodatabox.p.rapidapi.com", null, 400, 90, null);
        if (aviationstack == null) aviationstack = new Service("http://api.aviationstack.com/v1", null, 100, 90, null);
        if (ttl == null) ttl = new Ttl(null, null, null);
        if (live == null) live = new Live(null, null);
        if (negativeTtl == null) negativeTtl = Duration.ofHours(6);
        if (prewarm == null) prewarm = new Prewarm(null);
    }

    /** One outside service: where it is, its key, its free-tier limit and how much of it to use. */
    public record Service(String baseUrl, String key, int monthlyLimit, int capPercent, Duration timeout) {

        public Service {
            if (capPercent <= 0) capPercent = 90;
            if (timeout == null) timeout = Duration.ofSeconds(8);
        }

        /** The most calls a month may spend: the limit times the cap percentage. */
        public int cap() {
            return monthlyLimit * capPercent / 100;
        }

        public boolean enabled() {
            return key != null && !key.isBlank();
        }
    }

    /** How long AeroDataBox data is trusted, by how close the flight is. */
    public record Ttl(Duration farAhead, Duration withinThreeDays, Duration withinOneDay) {

        public Ttl {
            if (farAhead == null) farAhead = Duration.ofHours(72);
            if (withinThreeDays == null) withinThreeDays = Duration.ofHours(24);
            if (withinOneDay == null) withinOneDay = Duration.ofHours(1);
        }
    }

    /** AviationStack's extras: only inside the window before departure, and for this long. */
    public record Live(Duration window, Duration ttl) {

        public Live {
            if (window == null) window = Duration.ofHours(2);
            if (ttl == null) ttl = Duration.ofMinutes(15);
        }
    }

    public record Prewarm(String cron) {

        public Prewarm {
            if (cron == null || cron.isBlank()) cron = "0 0 1 * * *";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlightProperties.class)
    public static class Registration {
    }
}
```

- [ ] **Step 6: Write `FlightsConfig`**

```java
package com.josephinealinea.planner.flights;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Two clients, one per outside service. Named {@code …Http} on purpose: a
 * {@code @Bean RestClient aeroDataBoxClient} would collide with the
 * {@code @Component} of the same name and startup would die (see CLAUDE.md).
 * No status handlers: the clients read the status themselves, because an empty
 * body, a 429 and a 403 all mean different things.
 */
@Configuration
public class FlightsConfig {

    @Bean
    RestClient aeroDataBoxHttp(FlightProperties props) {
        return build(props.aerodatabox());
    }

    @Bean
    RestClient aviationStackHttp(FlightProperties props) {
        return build(props.aviationstack());
    }

    private static RestClient build(FlightProperties.Service service) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(service.timeout())
                .withReadTimeout(service.timeout());
        return RestClient.builder()
                .baseUrl(service.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "travel-planner/1.0")
                .build();
    }
}
```

- [ ] **Step 7: Add configuration** to `application.yml` under `app:` (after `rates:`):

```yaml
  flights:
    aerodatabox:
      base-url: https://aerodatabox.p.rapidapi.com
      key: ${AERODATABOX_KEY:}
      monthly-limit: ${AERODATABOX_MONTHLY_LIMIT:400}
      cap-percent: ${AERODATABOX_CAP_PERCENT:90}
      timeout: 8s
    aviationstack:
      base-url: http://api.aviationstack.com/v1
      key: ${AVIATIONSTACK_KEY:}
      monthly-limit: ${AVIATIONSTACK_MONTHLY_LIMIT:100}
      cap-percent: ${AVIATIONSTACK_CAP_PERCENT:90}
      timeout: 8s
    ttl:
      far-ahead: ${FLIGHT_TTL_FAR_AHEAD:72h}
      within-three-days: ${FLIGHT_TTL_WITHIN_THREE_DAYS:24h}
      within-one-day: ${FLIGHT_TTL_WITHIN_ONE_DAY:1h}
    live:
      window: ${FLIGHT_LIVE_WINDOW:2h}
      ttl: ${FLIGHT_LIVE_TTL:15m}
    negative-ttl: ${FLIGHT_NEGATIVE_TTL:6h}
    prewarm:
      cron: ${FLIGHTS_PREWARM_CRON:0 0 1 * * *}
```

- [ ] **Step 8: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'FlightNumbersTest' --tests 'FlightPropertiesTest' --tests 'YamlModeStartupTest'`
Expected: PASS (`YamlModeStartupTest` proves the app still boots with the new beans).

- [ ] **Step 9: Checkpoint.**

---

### Task 5: Repositories for the flight cache and the codeshare map

**Files:**
- Create: `flights/infra/FlightRecordRepository.java`, `YamlFlightRecordRepository.java`, `JdbcFlightRecordRepository.java`, `CodeshareRepository.java`, `YamlCodeshareRepository.java`, `JdbcCodeshareRepository.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/infra/FlightRecordRepositoryContract.java`, `YamlFlightRecordRepositoryContractTest.java`, `JdbcFlightRecordRepositoryContractTest.java`, `CodeshareRepositoryContract.java`, `YamlCodeshareRepositoryContractTest.java`, `JdbcCodeshareRepositoryContractTest.java`

**Interfaces:**
- Consumes: `FlightRecord`, `CodeshareMapping`, `YamlPaths.flightRecords()`, `flightCodeshares()`.
- Produces:
  - `FlightRecordRepository`: `Optional<FlightRecord> find(String flightNumber, LocalDate date)`, `void save(FlightRecord record)` (upsert by key), `List<FlightRecord> findAll()`
  - `CodeshareRepository`: `Optional<String> operatingFor(String bookedNumber)`, `void save(CodeshareMapping mapping)` (upsert), `List<CodeshareMapping> findAll()`

- [ ] **Step 1: Write the failing contract for the cache** `FlightRecordRepositoryContract`. Use a unique flight number per test (`"T" + counter`) so a shared Postgres does not leak between tests:

```java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class FlightRecordRepositoryContract {

    protected abstract FlightRecordRepository repository();

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    private static String number() {
        return "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    static FlightSchedule schedule() {
        Airport tll = new Airport("TLL", "EETN", "Tallinn Lennart Meri", "Tallinn", "EE", "Europe/Tallinn", 59.4133, 24.8328);
        Airport ams = new Airport("AMS", "EHAM", "Amsterdam Schiphol", "Amsterdam", "NL", "Europe/Amsterdam", 52.3086, 4.7639);
        return new FlightSchedule("BT 857", "Expected", "Airbus A220-300", "airBaltic",
                new FlightSchedule.Leg(tll, "2026-10-24T07:30+03:00", "2026-10-24T04:30Z", "2026-10-24T07:42+03:00", null, "1"),
                new FlightSchedule.Leg(ams, "2026-10-24T09:00+02:00", "2026-10-24T07:00Z", null, "2026-10-24T08:56+02:00", "2"),
                "2026-06-02T08:13Z");
    }

    static FlightLive live() {
        return new FlightLive("active",
                new FlightLive.Leg("8", null, 12, "2026-10-24T07:42:00"),
                new FlightLive.Leg("A4", "15", 0, "2026-10-24T08:57:00"));
    }

    @Test
    void everyFieldComesBackAsItWasSaved() {
        String number = number();
        FlightRecord full = new FlightRecord(number, DAY, schedule(), live(),
                Instant.parse("2026-10-22T01:00:04.211Z"), Instant.parse("2026-10-24T05:12:40.870Z"), false);

        repository().save(full);

        assertThat(repository().find(number, DAY)).get().usingRecursiveComparison().isEqualTo(full);
    }

    @Test
    void aZeroMinuteDelayIsAnAnswerNotAnAbsence() {
        String number = number();
        repository().save(new FlightRecord(number, DAY, null, live(), null, Instant.parse("2026-10-24T05:12:40Z"), false));

        assertThat(repository().find(number, DAY).orElseThrow().live().arrival().delayMinutes()).isEqualTo(0);
    }

    @Test
    void aStoredMissRoundTrips() {
        String number = number();
        FlightRecord miss = FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:07.020Z"));

        repository().save(miss);

        FlightRecord loaded = repository().find(number, DAY).orElseThrow();
        assertThat(loaded.notFound()).isTrue();
        assertThat(loaded.schedule()).isNull();
        assertThat(loaded.scheduleFetchedAt()).isEqualTo(miss.scheduleFetchedAt());
    }

    @Test
    void savingTwiceReplacesTheRowForThatFlightAndDate() {
        String number = number();
        repository().save(FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:00Z")));
        repository().save(new FlightRecord(number, DAY, schedule(), null,
                Instant.parse("2026-10-24T02:00:00Z"), null, false));

        assertThat(repository().findAll().stream().filter(r -> r.flightNumber().equals(number))).hasSize(1);
        assertThat(repository().find(number, DAY).orElseThrow().notFound()).isFalse();
    }

    @Test
    void theSameFlightOnAnotherDateIsAnotherRow() {
        String number = number();
        repository().save(FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:00Z")));

        assertThat(repository().find(number, DAY.plusDays(1))).isEmpty();
    }
}
```

- [ ] **Step 2: Write the codeshare contract** `CodeshareRepositoryContract`:

```java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class CodeshareRepositoryContract {

    protected abstract CodeshareRepository repository();

    private static String booked() {
        return "K" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    @Test
    void aSavedMappingIsFoundByTheBookedNumber() {
        String booked = booked();
        Instant at = Instant.parse("2026-09-26T09:14:22.480Z");

        repository().save(new CodeshareMapping(booked, "BT857", at));

        assertThat(repository().operatingFor(booked)).contains("BT857");
        assertThat(repository().findAll()).contains(new CodeshareMapping(booked, "BT857", at));
    }

    @Test
    void anUnknownNumberHasNoMapping() {
        assertThat(repository().operatingFor(booked())).isEmpty();
    }

    @Test
    void savingAgainReplacesTheMapping() {
        String booked = booked();
        repository().save(new CodeshareMapping(booked, "BT857", Instant.parse("2026-09-26T09:00:00Z")));
        repository().save(new CodeshareMapping(booked, "BT859", Instant.parse("2026-09-27T09:00:00Z")));

        assertThat(repository().operatingFor(booked)).contains("BT859");
    }
}
```

- [ ] **Step 3: Write the four subclasses.** The Yaml ones copy the `AppProperties` construction from `YamlApiUsageRepositoryContractTest` (Task 1 Step 4) and build `new YamlFlightRecordRepository(new YamlStore(), new YamlPaths(props))` / `new YamlCodeshareRepository(...)`. The Jdbc ones are:

```java
@PostgresTest
class JdbcFlightRecordRepositoryContractTest extends FlightRecordRepositoryContract {
    private final JdbcFlightRecordRepository repository = new JdbcFlightRecordRepository(PostgresTestDatabase.jdbc());
    @Override protected FlightRecordRepository repository() { return repository; }
}
```

```java
@PostgresTest
class JdbcCodeshareRepositoryContractTest extends CodeshareRepositoryContract {
    private final JdbcCodeshareRepository repository = new JdbcCodeshareRepository(PostgresTestDatabase.jdbc());
    @Override protected CodeshareRepository repository() { return repository; }
}
```

(with the same imports as the usage tests).

- [ ] **Step 4: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests '*FlightRecordRepositoryContract*' --tests '*CodeshareRepositoryContract*'`
Expected: FAIL (compilation: repositories missing).

- [ ] **Step 5: Write the interfaces and YAML implementations**

```java
// FlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.FlightRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The install-wide flight cache. Not trip-scoped: it holds public schedule facts. */
public interface FlightRecordRepository {

    Optional<FlightRecord> find(String flightNumber, LocalDate departureDate);

    /** Upsert by (flightNumber, departureDate). */
    void save(FlightRecord record);

    List<FlightRecord> findAll();
}
```

```java
// YamlFlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@code data/flights/records.yml}. {@code synchronized} is the lock: YAML mode is single-instance. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlFlightRecordRepository implements FlightRecordRepository {

    private static final TypeReference<List<FlightRecord>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlFlightRecordRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized Optional<FlightRecord> find(String flightNumber, LocalDate departureDate) {
        return findAll().stream()
                .filter(r -> r.flightNumber().equals(flightNumber) && r.departureDate().equals(departureDate))
                .findFirst();
    }

    @Override
    public synchronized void save(FlightRecord record) {
        List<FlightRecord> rows = store.readList(paths.flightRecords(), ROWS);
        rows.removeIf(r -> r.flightNumber().equals(record.flightNumber())
                && r.departureDate().equals(record.departureDate()));
        rows.add(record);
        store.write(paths.flightRecords(), rows);
    }

    @Override
    public synchronized List<FlightRecord> findAll() {
        return store.readList(paths.flightRecords(), ROWS);
    }
}
```

```java
// CodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;

import java.util.List;
import java.util.Optional;

/** Booked number to operating number. Permanent, install-wide. */
public interface CodeshareRepository {

    Optional<String> operatingFor(String bookedNumber);

    /** Upsert by booked number. */
    void save(CodeshareMapping mapping);

    List<CodeshareMapping> findAll();
}
```

```java
// YamlCodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code data/flights/codeshares.yml}. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlCodeshareRepository implements CodeshareRepository {

    private static final TypeReference<List<CodeshareMapping>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlCodeshareRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized Optional<String> operatingFor(String bookedNumber) {
        return findAll().stream()
                .filter(m -> m.bookedNumber().equals(bookedNumber))
                .map(CodeshareMapping::operatingNumber)
                .findFirst();
    }

    @Override
    public synchronized void save(CodeshareMapping mapping) {
        List<CodeshareMapping> rows = store.readList(paths.flightCodeshares(), ROWS);
        rows.removeIf(m -> m.bookedNumber().equals(mapping.bookedNumber()));
        rows.add(mapping);
        store.write(paths.flightCodeshares(), rows);
    }

    @Override
    public synchronized List<CodeshareMapping> findAll() {
        return store.readList(paths.flightCodeshares(), ROWS);
    }
}
```

- [ ] **Step 6: Write the JDBC implementations**

```java
// JdbcFlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@code flight_records}. {@code schedule} and {@code live} are jsonb documents
 * (bound as text and cast in the statement, like {@code weather_records.details}).
 * {@code not_found} is NOT NULL with a default, and an explicit NULL would not
 * take that default, so it is always written as a real boolean.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcFlightRecordRepository implements FlightRecordRepository {

    private final JdbcClient jdbc;

    public JdbcFlightRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FlightRecord> find(String flightNumber, LocalDate departureDate) {
        return jdbc.sql("SELECT * FROM flight_records WHERE flight_number = :n AND departure_date = :d")
                .param("n", flightNumber)
                .param("d", departureDate)
                .query((rs, i) -> map(rs))
                .optional();
    }

    @Override
    public void save(FlightRecord record) {
        jdbc.sql("""
                        INSERT INTO flight_records
                            (flight_number, departure_date, schedule, live,
                             schedule_fetched_at, live_fetched_at, not_found)
                        VALUES (:n, :d, :schedule::jsonb, :live::jsonb, :sf, :lf, :nf)
                        ON CONFLICT (flight_number, departure_date) DO UPDATE SET
                            schedule = EXCLUDED.schedule, live = EXCLUDED.live,
                            schedule_fetched_at = EXCLUDED.schedule_fetched_at,
                            live_fetched_at = EXCLUDED.live_fetched_at,
                            not_found = EXCLUDED.not_found
                        """)
                .param("n", record.flightNumber())
                .param("d", record.departureDate())
                .param("schedule", JdbcValues.json(record.schedule()))
                .param("live", JdbcValues.json(record.live()))
                .param("sf", JdbcValues.timestamptz(record.scheduleFetchedAt()))
                .param("lf", JdbcValues.timestamptz(record.liveFetchedAt()))
                .param("nf", record.notFound())
                .update();
    }

    @Override
    public List<FlightRecord> findAll() {
        return jdbc.sql("SELECT * FROM flight_records ORDER BY flight_number, departure_date")
                .query((rs, i) -> map(rs))
                .list();
    }

    private static FlightRecord map(ResultSet rs) throws SQLException {
        return new FlightRecord(
                rs.getString("flight_number"),
                JdbcValues.localDate(rs, "departure_date"),
                JdbcValues.json(rs, "schedule", FlightSchedule.class),
                JdbcValues.json(rs, "live", FlightLive.class),
                JdbcValues.instant(rs, "schedule_fetched_at"),
                JdbcValues.instant(rs, "live_fetched_at"),
                rs.getBoolean("not_found"));
    }
}
```

```java
// JdbcCodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code codeshare_mappings}. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcCodeshareRepository implements CodeshareRepository {

    private final JdbcClient jdbc;

    public JdbcCodeshareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> operatingFor(String bookedNumber) {
        return jdbc.sql("SELECT operating_number FROM codeshare_mappings WHERE booked_number = :b")
                .param("b", bookedNumber)
                .query(String.class)
                .optional();
    }

    @Override
    public void save(CodeshareMapping mapping) {
        jdbc.sql("""
                        INSERT INTO codeshare_mappings (booked_number, operating_number, resolved_at)
                        VALUES (:b, :o, :at)
                        ON CONFLICT (booked_number) DO UPDATE SET
                            operating_number = EXCLUDED.operating_number, resolved_at = EXCLUDED.resolved_at
                        """)
                .param("b", mapping.bookedNumber())
                .param("o", mapping.operatingNumber())
                .param("at", JdbcValues.timestamptz(mapping.resolvedAt()))
                .update();
    }

    @Override
    public List<CodeshareMapping> findAll() {
        return jdbc.sql("SELECT * FROM codeshare_mappings ORDER BY booked_number")
                .query((rs, i) -> new CodeshareMapping(rs.getString("booked_number"),
                        rs.getString("operating_number"), JdbcValues.instant(rs, "resolved_at")))
                .list();
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests '*FlightRecordRepositoryContract*' --tests '*CodeshareRepositoryContract*' --tests 'JdbcFlight*' --tests 'JdbcCodeshare*' --tests 'YamlFlight*' --tests 'YamlCodeshare*'`
Expected: PASS, `skipped="0"` for the Jdbc classes. If the recursive comparison in `everyFieldComesBackAsItWasSaved` differs on Instant precision for Postgres, truncate the fixture instants to micros (they already carry only milliseconds, so it should not).

- [ ] **Step 8: Checkpoint** — also run `./gradlew test --tests 'JdbcTripRepositoryContractTest'` and confirm `everyTableReferencingTripsIsCovered` still passes (none of the new tables references `trips`).

---

### Task 6: The AeroDataBox client

**Files:**
- Create: `flights/AeroDataBoxClient.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/AeroDataBoxClientTest.java`

**Interfaces:**
- Consumes: `FlightProperties.Service`, `ApiUsageService.tryAcquire`, `Fetch`, `FlightSchedule`, `Airport`, `RestClient`.
- Produces: `AeroDataBoxClient(RestClient aeroDataBoxHttp, FlightProperties props, ApiUsageService usage)` with `Fetch<FlightSchedule> flight(String number, LocalDate departureDate)`; constant `AeroDataBoxClient.SERVICE = "aerodatabox"`.

- [ ] **Step 1: Write the failing test.** The response below is the real one captured for BT857 on 2026-10-24 (departure-role query). `FlightSchedule` field expectations come straight from it.

```java
package com.josephinealinea.planner.flights;

import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AeroDataBoxClientTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    /** Real payload, trimmed of the fields the app does not read. */
    private static final String BT857 = """
            [{"greatCircleDistance":{"km":1476.01},
              "departure":{"airport":{"icao":"EETN","iata":"TLL","name":"Tallinn Lennart Meri","shortName":"Lennart Meri",
                  "municipalityName":"Tallinn","location":{"lat":59.4133,"lon":24.8328},"countryCode":"EE","timeZone":"Europe/Tallinn"},
                "scheduledTime":{"utc":"2026-10-24 04:30Z","local":"2026-10-24 07:30+03:00"},"quality":["Basic"]},
              "arrival":{"airport":{"icao":"EHAM","iata":"AMS","name":"Amsterdam Schiphol","shortName":"Schiphol",
                  "municipalityName":"Amsterdam","location":{"lat":52.3086,"lon":4.763889},"countryCode":"NL","timeZone":"Europe/Amsterdam"},
                "scheduledTime":{"utc":"2026-10-24 07:00Z","local":"2026-10-24 09:00+02:00"},
                "predictedTime":{"utc":"2026-10-24 06:56Z","local":"2026-10-24 08:56+02:00"},"quality":["Basic"]},
              "lastUpdatedUtc":"2026-06-02 08:13Z","number":"BT 857","status":"Expected","codeshareStatus":"Unknown",
              "isCargo":false,"aircraft":{"model":"Airbus A220-300"},"airline":{"name":"airBaltic","iata":"BT","icao":"BTI"}}]
            """;

    /** Counts real acquisitions so a test can see what the client spent. */
    private static final class Counting implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service, 0);
            if (now >= cap) return false;
            counts.put(service, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service, 0);
        }
    }

    private final Counting usage = new Counting();

    private AeroDataBoxClient client(CannedHttp http, String key) {
        FlightProperties props = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", key, 400, 90, null),
                null, null, null, null, null);
        return new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage));
    }

    @Test
    void readsAFlightIncludingAirportsTimesAndCoordinates() {
        CannedHttp http = new CannedHttp().ok(BT857);

        Fetch<FlightSchedule> result = client(http, "key").flight("BT857", DAY);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        FlightSchedule flight = result.value();
        assertThat(flight.number()).isEqualTo("BT857");
        assertThat(flight.aircraftModel()).isEqualTo("Airbus A220-300");
        assertThat(flight.departure().airport().iata()).isEqualTo("TLL");
        assertThat(flight.departure().airport().city()).isEqualTo("Tallinn");
        assertThat(flight.departure().airport().timezone()).isEqualTo("Europe/Tallinn");
        assertThat(flight.departure().airport().lat()).isEqualTo(59.4133);
        assertThat(flight.departure().scheduledLocal()).isEqualTo("2026-10-24T07:30+03:00");
        assertThat(flight.departure().scheduledUtc()).isEqualTo("2026-10-24T04:30Z");
        assertThat(flight.arrival().predictedLocal()).isEqualTo("2026-10-24T08:56+02:00");
        assertThat(http.asked().get(0).toString()).contains("/flights/number/BT857/2026-10-24")
                .contains("dateLocalRole=Departure");
    }

    /** The service answers "no such flight" with an empty body, not an error. */
    @Test
    void anEmptyBodyIsNotFound() {
        assertThat(client(new CannedHttp().ok(""), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(client(new CannedHttp().ok("[]"), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(client(new CannedHttp().status(204, ""), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void aFlightOnAnotherDateIsNotFound() {
        assertThat(client(new CannedHttp().ok(BT857), "key").flight("BT857", DAY.plusDays(1)).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    /** Review focus 6: these are outages, never misses. */
    @Test
    void throttlingAndServerErrorsAreUnavailable() {
        assertThat(client(new CannedHttp().status(429, "{\"message\":\"Too many requests\"}"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(500, "boom"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(403, "{}"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    @Test
    void noKeyMeansNoCallAndNothingSpent() {
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(client(http, "").flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isZero();
        assertThat(usage.counts).isEmpty();
    }

    @Test
    void theCapStopsTheCall() {
        FlightProperties props = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 1, 100, null),
                null, null, null, null, null);
        CannedHttp http = new CannedHttp().ok(BT857).ok(BT857);
        AeroDataBoxClient client = new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage));

        assertThat(client.flight("BT857", DAY).status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(client.flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'AeroDataBoxClientTest'`
Expected: FAIL (compilation: `AeroDataBoxClient` missing).

- [ ] **Step 3: Write the client**

```java
package com.josephinealinea.planner.flights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * AeroDataBox on RapidAPI: schedule, airports, times with real offsets,
 * terminal, status and aircraft. The source of truth for a flight.
 *
 * <b>"No such flight" is an empty body, not an error.</b> It is reported as
 * NOT_FOUND. Anything that stops an answer (no key, cap reached, timeout, 4xx,
 * 5xx) is UNAVAILABLE and is never to be remembered as a miss.
 *
 * <b>Times.</b> The provider writes {@code 2026-10-24 07:30+03:00}; the space
 * becomes a {@code T} so every stored time is ISO-8601. The {@code local} value
 * is airport wall-clock time, which is what the itinerary stores.
 */
@Service
public class AeroDataBoxClient {

    public static final String SERVICE = "aerodatabox";

    private static final Logger log = LoggerFactory.getLogger(AeroDataBoxClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Raw(int status, String body) {}

    private final RestClient http;
    private final FlightProperties.Service config;
    private final ApiUsageService usage;

    // The parameter name is the bean name Spring injects by: see FlightsConfig.
    public AeroDataBoxClient(RestClient aeroDataBoxHttp, FlightProperties props, ApiUsageService usage) {
        this.http = aeroDataBoxHttp;
        this.config = props.aerodatabox();
        this.usage = usage;
    }

    /** The flight departing on {@code departureDate} (local date at the origin). */
    public Fetch<FlightSchedule> flight(String number, LocalDate departureDate) {
        if (!config.enabled()) return Fetch.unavailable();
        if (!usage.tryAcquire(SERVICE, config.cap())) {
            log.info("AeroDataBox monthly cap ({}) reached; not calling", config.cap());
            return Fetch.unavailable();
        }
        try {
            Raw raw = http.get()
                    .uri(uri -> uri.path("/flights/number/{n}/{d}")
                            .queryParam("dateLocalRole", "Departure")
                            .build(number, departureDate.toString()))
                    // RapidAPI wants the host it fronts; it is the base URL's own host.
                    .header("X-RapidAPI-Key", config.key())
                    .header("X-RapidAPI-Host", URI.create(config.baseUrl()).getHost())
                    .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            return interpret(raw, departureDate);
        } catch (Exception e) {
            // A read timeout here means throttling, not a bug in the request.
            log.warn("AeroDataBox lookup of {} failed: {}", number, e.getMessage());
            return Fetch.unavailable();
        }
    }

    private Fetch<FlightSchedule> interpret(Raw raw, LocalDate date) throws Exception {
        if (raw.status() == 204 || raw.status() == 404) return Fetch.notFound();
        if (raw.status() != 200) {
            log.warn("AeroDataBox answered {}", raw.status());
            return Fetch.unavailable();
        }
        if (raw.body() == null || raw.body().isBlank()) return Fetch.notFound();

        JsonNode root = JSON.readTree(raw.body());
        if (!root.isArray() || root.isEmpty()) return Fetch.notFound();
        for (JsonNode flight : root) {
            String local = text(flight.path("departure").path("scheduledTime"), "local");
            if (local != null && local.startsWith(date.toString())) return Fetch.found(schedule(flight));
        }
        return Fetch.notFound();
    }

    private static FlightSchedule schedule(JsonNode f) {
        return new FlightSchedule(
                FlightNumbers.normalise(text(f, "number")),
                text(f, "status"),
                text(f.path("aircraft"), "model"),
                text(f.path("airline"), "name"),
                leg(f.path("departure")),
                leg(f.path("arrival")),
                iso(text(f, "lastUpdatedUtc")));
    }

    private static FlightSchedule.Leg leg(JsonNode n) {
        return new FlightSchedule.Leg(
                airport(n.path("airport")),
                iso(text(n.path("scheduledTime"), "local")),
                iso(text(n.path("scheduledTime"), "utc")),
                iso(text(n.path("revisedTime"), "local")),
                iso(text(n.path("predictedTime"), "local")),
                text(n, "terminal"));
    }

    private static Airport airport(JsonNode a) {
        JsonNode location = a.path("location");
        return new Airport(
                text(a, "iata"), text(a, "icao"), text(a, "name"), text(a, "municipalityName"),
                text(a, "countryCode"), text(a, "timeZone"),
                location.hasNonNull("lat") ? location.get("lat").asDouble() : null,
                location.hasNonNull("lon") ? location.get("lon").asDouble() : null);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** {@code 2026-10-24 07:30+03:00} becomes {@code 2026-10-24T07:30+03:00}. */
    static String iso(String provider) {
        return provider == null ? null : provider.replaceFirst(" ", "T");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'AeroDataBoxClientTest'`
Expected: PASS.

- [ ] **Step 5: Live verification (needs the owner's approval first).** The base URL and the two RapidAPI headers are assumptions taken from how RapidAPI fronts AeroDataBox; the canned tests cannot prove them. **Ask the owner before spending a call.** With approval, one call: start the API with `AERODATABOX_KEY` set from the shell's `RAPIDAPI_KEY`, register nothing else, and request BT857 for a near date through the lookup endpoint (Task 9). If the answer is `unavailable` with a 401/403 in the log, the host or headers differ; fix `FlightsConfig`/`AeroDataBoxClient` and re-test. Do not loop: one attempt, then report.

- [ ] **Step 6: Checkpoint.**

---

### Task 7: The AviationStack client

**Files:**
- Create: `flights/AviationStackClient.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/AviationStackClientTest.java`

**Interfaces:**
- Consumes: `FlightProperties.Service`, `ApiUsageService`, `Fetch`, `FlightLive`, `FlightNumbers`.
- Produces: `AviationStackClient(RestClient aviationStackHttp, FlightProperties props, ApiUsageService usage)` with `Fetch<String> codeshareOf(String number)` (the operating number, upper case) and `Fetch<FlightLive> live(String number, LocalDate date)`; `AviationStackClient.SERVICE = "aviationstack"`.

- [ ] **Step 1: Write the failing test.** The rows are the real ones captured for KL2842 (the codeshare with gate `8`, baggage `15`, delay 12), trimmed.

```java
package com.josephinealinea.planner.flights;

import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AviationStackClientTest {

    private static final String KL2842 = """
            {"pagination":{"limit":100,"offset":0,"count":2,"total":2},"data":[
             {"flight_date":"2026-09-26","flight_status":"active",
              "departure":{"iata":"TLL","gate":"8","delay":12,
                "scheduled":"2026-09-26T07:30:00+00:00","actual":"2026-09-26T07:42:00+00:00"},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"15","delay":0,
                "scheduled":"2026-09-26T09:00:00+00:00","actual":"2026-09-26T08:57:00+00:00"},
              "airline":{"name":"KLM","iata":"KL"},
              "flight":{"number":"2842","iata":"KL2842","codeshared":{"airline_name":"airbaltic",
                "airline_iata":"bt","flight_number":"857","flight_iata":"bt857","flight_icao":"bti857"}}},
             {"flight_date":"2026-09-25","flight_status":"landed",
              "departure":{"iata":"TLL","gate":"9","delay":7,"actual":"2026-09-25T07:37:00+00:00"},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"16","delay":0,"actual":"2026-09-25T08:38:00+00:00"},
              "flight":{"number":"2842","iata":"KL2842","codeshared":{"flight_iata":"bt857"}}}]}
            """;

    /** A flight that simply is not a codeshare. */
    private static final String PLAIN = """
            {"data":[{"flight_date":"2026-09-26","flight_status":"scheduled",
              "departure":{"iata":"TLL","gate":null,"delay":null},
              "arrival":{"iata":"AMS","gate":null,"baggage":null,"delay":null},
              "flight":{"number":"857","iata":"BT857","codeshared":null}}]}
            """;

    private static final String RESTRICTED = """
            {"error":{"code":"function_access_restricted","message":"Your current subscription plan does not support this API function."}}
            """;

    private final ApiUsageRepository memory = new ApiUsageRepository() {
        int used;

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            if (used >= cap) return false;
            used++;
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return used;
        }
    };

    private AviationStackClient client(CannedHttp http, String key) {
        FlightProperties props = new FlightProperties(null,
                new FlightProperties.Service("http://api.aviationstack.com/v1", key, 100, 90, null),
                null, null, null, null);
        return new AviationStackClient(http.client(), props, new ApiUsageService(memory));
    }

    @Test
    void aCodeshareNamesItsOperatingFlightInUpperCase() {
        Fetch<String> result = client(new CannedHttp().ok(KL2842), "key").codeshareOf("KL2842");

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.value()).isEqualTo("BT857");
    }

    /** Review focus 3: rows with no codeshare are a clean miss, not a crash or a bogus mapping. */
    @Test
    void aPlainFlightHasNoCodeshare() {
        assertThat(client(new CannedHttp().ok(PLAIN), "key").codeshareOf("BT857").status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void noRowsAtAllIsNotFound() {
        assertThat(client(new CannedHttp().ok("{\"data\":[]}"), "key").codeshareOf("ZZ9999").status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void liveExtrasComeFromTheRowForThatDateWithAirportLocalTimes() {
        Fetch<FlightLive> result = client(new CannedHttp().ok(KL2842), "key").live("KL2842", LocalDate.of(2026, 9, 26));

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        FlightLive live = result.value();
        assertThat(live.status()).isEqualTo("active");
        assertThat(live.departure().gate()).isEqualTo("8");
        assertThat(live.departure().delayMinutes()).isEqualTo(12);
        // The provider labels local time as UTC; the offset is dropped, not converted.
        assertThat(live.departure().actualLocal()).isEqualTo("2026-09-26T07:42:00");
        assertThat(live.arrival().gate()).isEqualTo("A4");
        assertThat(live.arrival().baggageBelt()).isEqualTo("15");
        assertThat(live.arrival().delayMinutes()).isEqualTo(0);
    }

    @Test
    void aDateItHasNoRowForIsNotFound() {
        assertThat(client(new CannedHttp().ok(KL2842), "key").live("KL2842", LocalDate.of(2026, 10, 24)).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void anErrorObjectIsUnavailableNotAMiss() {
        assertThat(client(new CannedHttp().status(403, RESTRICTED), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().ok(RESTRICTED), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(500, "boom"), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    @Test
    void noKeyMeansNoCall() {
        CannedHttp http = new CannedHttp().ok(KL2842);

        assertThat(client(http, "").codeshareOf("KL2842").status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isZero();
    }

    @Test
    void theKeyIsSentAsAQueryParameterAndNeverLogged() {
        CannedHttp http = new CannedHttp().ok(KL2842);

        client(http, "secret123").codeshareOf("kl 2842");

        assertThat(http.asked().get(0).toString()).contains("flight_iata=KL2842").contains("access_key=secret123");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'AviationStackClientTest'`
Expected: FAIL (compilation).

- [ ] **Step 3: Write the client**

```java
package com.josephinealinea.planner.flights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * AviationStack, used for two things only: finding a codeshare's operating
 * flight, and the live extras (gate, baggage belt, delay, actual times) close to
 * departure. Never for scheduled times or airports.
 *
 * <b>Its timestamps are wrong on purpose to know about.</b> It labels airport
 * local time as UTC ({@code 07:30+00:00} for a 07:30 Tallinn departure). The
 * offset is dropped, not applied.
 *
 * <b>Free plan.</b> HTTP only, so the key travels in plain text; 100 calls a
 * month; and the flight endpoint returns only flights around today (a
 * {@code flight_date} filter answers 403 {@code function_access_restricted}).
 * The key is a query parameter and is never logged.
 */
@Service
public class AviationStackClient {

    public static final String SERVICE = "aviationstack";

    private static final Logger log = LoggerFactory.getLogger(AviationStackClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Raw(int status, String body) {}

    private final RestClient http;
    private final FlightProperties.Service config;
    private final ApiUsageService usage;

    public AviationStackClient(RestClient aviationStackHttp, FlightProperties props, ApiUsageService usage) {
        this.http = aviationStackHttp;
        this.config = props.aviationstack();
        this.usage = usage;
    }

    /** The operating flight number when {@code number} is a codeshare, upper case. */
    public Fetch<String> codeshareOf(String number) {
        Fetch<JsonNode> rows = rows(number);
        if (rows.status() != Fetch.Status.FOUND) return Fetch.of(rows.status());
        for (JsonNode row : rows.value()) {
            JsonNode shared = row.path("flight").path("codeshared");
            if (shared.hasNonNull("flight_iata")) {
                return Fetch.found(FlightNumbers.normalise(shared.get("flight_iata").asText()));
            }
        }
        return Fetch.notFound();
    }

    /** Gate, baggage, delay and actual times for the row on {@code date}. */
    public Fetch<FlightLive> live(String number, LocalDate date) {
        Fetch<JsonNode> rows = rows(number);
        if (rows.status() != Fetch.Status.FOUND) return Fetch.of(rows.status());
        for (JsonNode row : rows.value()) {
            if (date.toString().equals(text(row, "flight_date"))) return Fetch.found(live(row));
        }
        return Fetch.notFound();
    }

    private Fetch<JsonNode> rows(String number) {
        if (!config.enabled()) return Fetch.unavailable();
        if (!usage.tryAcquire(SERVICE, config.cap())) {
            log.info("AviationStack monthly cap ({}) reached; not calling", config.cap());
            return Fetch.unavailable();
        }
        try {
            Raw raw = http.get()
                    .uri(uri -> uri.path("/flights")
                            .queryParam("access_key", config.key())
                            .queryParam("flight_iata", FlightNumbers.normalise(number))
                            .build())
                    .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (raw.status() != 200) {
                log.warn("AviationStack answered {}", raw.status());
                return Fetch.unavailable();
            }
            JsonNode root = JSON.readTree(raw.body());
            // An error arrives as a 200 or a 403 with an "error" object.
            if (root.has("error")) {
                log.warn("AviationStack error: {}", text(root.path("error"), "code"));
                return Fetch.unavailable();
            }
            JsonNode data = root.path("data");
            return data.isArray() && !data.isEmpty() ? Fetch.found(data) : Fetch.notFound();
        } catch (Exception e) {
            // Deliberately not the URI: it carries the key.
            log.warn("AviationStack lookup of {} failed: {}", number, e.getMessage());
            return Fetch.unavailable();
        }
    }

    private static FlightLive live(JsonNode row) {
        return new FlightLive(text(row, "flight_status"), leg(row.path("departure")), leg(row.path("arrival")));
    }

    private static FlightLive.Leg leg(JsonNode n) {
        return new FlightLive.Leg(
                text(n, "gate"),
                text(n, "baggage"),
                n.hasNonNull("delay") ? n.get("delay").asInt() : null,
                local(text(n, "actual")));
    }

    /** {@code 2026-09-26T07:42:00+00:00} becomes {@code 2026-09-26T07:42:00}: local, mislabelled as UTC. */
    static String local(String provider) {
        return provider == null || provider.length() < 19 ? provider : provider.substring(0, 19);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
```

`Fetch.of(Status)` is used above to pass a non-FOUND status through; add it to `Fetch`:

```java
    /** Carries a non-found outcome across a change of value type. */
    public static <T> Fetch<T> of(Status status) {
        return new Fetch<>(status, null);
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'AviationStackClientTest' --tests 'AeroDataBoxClientTest'`
Expected: PASS.

- [ ] **Step 5: Checkpoint** — the key never appears in a log line (grep the client for `log.` calls: none passes a URI or the key).

---

### Task 8: Freshness rules and the cache-aware schedule access

**Files:**
- Create: `flights/api/FlightFreshness.java`, `flights/api/FlightData.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/api/FlightFreshnessTest.java`, `FlightDataTest.java`

**Interfaces:**
- Consumes: `FlightProperties`, `FlightRecord`, `FlightSchedule`, `FlightRecordRepository`, `AeroDataBoxClient.flight`, `Fetch`.
- Produces:
  - `FlightFreshness(FlightProperties)` with: `Duration scheduleTtl(Instant now, Instant departure)`, `boolean scheduleFresh(FlightRecord r, Instant now, Instant departureHint)`, `boolean negativeFresh(FlightRecord r, Instant now)`, `boolean liveFresh(FlightRecord r, Instant now)`, `boolean inLiveWindow(Instant now, Instant departure)`, `boolean landed(FlightSchedule s, Instant now)`, `static Instant departureOf(FlightSchedule s)`
  - `FlightData(AeroDataBoxClient, FlightRecordRepository, FlightFreshness)` (`@Autowired` real constructor + a package-private one with `Clock`) with `Result schedule(String number, LocalDate date, Instant departureHint)`, `record Result(Fetch.Status status, FlightRecord record, boolean stale)`

- [ ] **Step 1: Write the failing freshness test**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FlightFreshnessTest {

    private final FlightFreshness rules =
            new FlightFreshness(new FlightProperties(null, null, null, null, null, null));

    private static final Instant DEPARTURE = Instant.parse("2026-10-24T04:30:00Z");

    private static FlightSchedule schedule(String status, String arrivalUtc) {
        return new FlightSchedule("BT857", status, null, null,
                new FlightSchedule.Leg(null, null, "2026-10-24T04:30Z", null, null, null),
                new FlightSchedule.Leg(null, null, arrivalUtc, null, null, null), null);
    }

    private static FlightRecord record(FlightSchedule schedule, Instant fetched) {
        return new FlightRecord("BT857", LocalDate.of(2026, 10, 24), schedule, null, fetched, null, false);
    }

    @Test
    void theTierFollowsHowCloseTheFlightIs() {
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofDays(10)), DEPARTURE)).isEqualTo(Duration.ofHours(72));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(73)), DEPARTURE)).isEqualTo(Duration.ofHours(72));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(72)), DEPARTURE)).isEqualTo(Duration.ofHours(24));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(25)), DEPARTURE)).isEqualTo(Duration.ofHours(24));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(24)), DEPARTURE)).isEqualTo(Duration.ofHours(1));
        assertThat(rules.scheduleTtl(DEPARTURE.plus(Duration.ofHours(1)), DEPARTURE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void anUnknownDepartureUsesTheLongestTtl() {
        assertThat(rules.scheduleTtl(Instant.now(), null)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void aRecordIsFreshUntilItsTierTtlLapses() {
        Instant fetched = Instant.parse("2026-10-23T08:00:00Z"); // 22.5 h before: 1 h tier
        FlightRecord r = record(schedule("Expected", "2026-10-24T07:00Z"), fetched);

        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(59)), DEPARTURE)).isTrue();
        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(61)), DEPARTURE)).isFalse();
    }

    @Test
    void aLandedFlightIsFrozenForever() {
        FlightRecord r = record(schedule("Arrived", "2026-10-24T07:00Z"), Instant.parse("2026-10-24T08:00:00Z"));

        assertThat(rules.scheduleFresh(r, Instant.parse("2026-12-01T00:00:00Z"), DEPARTURE)).isTrue();
    }

    @Test
    void landedMeansAnArrivedStatusOrThreeHoursPastTheScheduledArrival() {
        Instant arrival = Instant.parse("2026-10-24T07:00:00Z");

        assertThat(rules.landed(schedule("Arrived", "2026-10-24T07:00Z"), arrival.minus(Duration.ofHours(1)))).isTrue();
        assertThat(rules.landed(schedule("Canceled", "2026-10-24T07:00Z"), arrival.minus(Duration.ofHours(1)))).isTrue();
        assertThat(rules.landed(schedule("EnRoute", "2026-10-24T07:00Z"), arrival.plus(Duration.ofHours(2)))).isFalse();
        assertThat(rules.landed(schedule("EnRoute", "2026-10-24T07:00Z"), arrival.plus(Duration.ofHours(3)))).isTrue();
    }

    @Test
    void theLiveWindowOpensTwoHoursBeforeDeparture() {
        assertThat(rules.inLiveWindow(DEPARTURE.minus(Duration.ofMinutes(121)), DEPARTURE)).isFalse();
        assertThat(rules.inLiveWindow(DEPARTURE.minus(Duration.ofMinutes(119)), DEPARTURE)).isTrue();
        assertThat(rules.inLiveWindow(DEPARTURE.plus(Duration.ofHours(1)), DEPARTURE)).isTrue();
    }

    @Test
    void liveExtrasAreFreshForFifteenMinutes() {
        Instant at = Instant.parse("2026-10-24T03:00:00Z");
        FlightRecord r = new FlightRecord("BT857", LocalDate.of(2026, 10, 24), null, null, null, at, false);

        assertThat(rules.liveFresh(r, at.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(rules.liveFresh(r, at.plus(Duration.ofMinutes(16)))).isFalse();
        assertThat(rules.liveFresh(new FlightRecord("X", null, null, null, null, null, false), at)).isFalse();
    }

    @Test
    void aMissIsFreshForTheNegativeTtl() {
        Instant at = Instant.parse("2026-10-24T01:00:00Z");
        FlightRecord miss = FlightRecord.missing("KL2842", LocalDate.of(2026, 10, 24), at);

        assertThat(rules.negativeFresh(miss, at.plus(Duration.ofHours(5)))).isTrue();
        assertThat(rules.negativeFresh(miss, at.plus(Duration.ofHours(7)))).isFalse();
    }
}
```

- [ ] **Step 2: Write `FlightFreshness`**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * The time rules for the flight cache, as pure functions of "now" so a test can
 * pin them. Nothing here reads a clock or touches a store.
 */
@Component
public class FlightFreshness {

    /** AeroDataBox statuses after which nothing more will change. */
    private static final Set<String> ENDED = Set.of("Arrived", "Landed", "Canceled");

    private static final Duration LANDED_GRACE = Duration.ofHours(3);

    private final FlightProperties props;

    public FlightFreshness(FlightProperties props) {
        this.props = props;
    }

    /** How long a schedule fetched now is trusted, by how close departure is. */
    public Duration scheduleTtl(Instant now, Instant departure) {
        if (departure == null) return props.ttl().farAhead();
        Duration until = Duration.between(now, departure);
        if (until.compareTo(Duration.ofHours(24)) <= 0) return props.ttl().withinOneDay();
        if (until.compareTo(Duration.ofHours(72)) <= 0) return props.ttl().withinThreeDays();
        return props.ttl().farAhead();
    }

    /**
     * Frozen once landed (a finished flight never changes); otherwise fresh while
     * younger than the TTL of the tier it is in now.
     */
    public boolean scheduleFresh(FlightRecord record, Instant now, Instant departureHint) {
        if (record.schedule() == null || record.scheduleFetchedAt() == null) return false;
        if (landed(record.schedule(), now)) return true;
        Instant departure = departureOf(record.schedule());
        if (departure == null) departure = departureHint;
        Duration age = Duration.between(record.scheduleFetchedAt(), now);
        return age.compareTo(scheduleTtl(now, departure)) < 0;
    }

    public boolean negativeFresh(FlightRecord record, Instant now) {
        return record.notFound() && record.scheduleFetchedAt() != null
                && Duration.between(record.scheduleFetchedAt(), now).compareTo(props.negativeTtl()) < 0;
    }

    public boolean liveFresh(FlightRecord record, Instant now) {
        return record.liveFetchedAt() != null
                && Duration.between(record.liveFetchedAt(), now).compareTo(props.live().ttl()) < 0;
    }

    /** From the window before departure until landing (the caller checks landed). */
    public boolean inLiveWindow(Instant now, Instant departure) {
        return departure != null && !now.isBefore(departure.minus(props.live().window()));
    }

    /** An ended status, or three hours past the scheduled arrival, so a stuck status cannot stay live forever. */
    public boolean landed(FlightSchedule schedule, Instant now) {
        if (schedule.status() != null && ENDED.contains(schedule.status())) return true;
        Instant arrival = parse(schedule.arrival() == null ? null : schedule.arrival().scheduledUtc());
        return arrival != null && !now.isBefore(arrival.plus(LANDED_GRACE));
    }

    public static Instant departureOf(FlightSchedule schedule) {
        return schedule == null || schedule.departure() == null ? null : parse(schedule.departure().scheduledUtc());
    }

    private static Instant parse(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso).toInstant();
    }
}
```

- [ ] **Step 3: Run the freshness test**

Run: `cd planner-api && ./gradlew test --tests 'FlightFreshnessTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing `FlightDataTest`** (`flights/api/FlightDataTest.java`). It runs the real `AeroDataBoxClient` over `CannedHttp`, an in-memory record store and a fixed clock, so it proves the cache rules end to end. The in-memory helpers below are reused by Tasks 9 to 11: put them in `src/test/java/com/josephinealinea/planner/flights/api/FlightFixtures.java` as package-private statics.

```java
// FlightFixtures.java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FlightFixtures {

    private FlightFixtures() {}

    static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** The real BT857 payload for 2026-10-24 (departure 04:30Z, arrival 07:00Z), trimmed. */
    static final String BT857 = """
            [{"departure":{"airport":{"icao":"EETN","iata":"TLL","name":"Tallinn Lennart Meri","municipalityName":"Tallinn",
                  "location":{"lat":59.4133,"lon":24.8328},"countryCode":"EE","timeZone":"Europe/Tallinn"},
                "scheduledTime":{"utc":"2026-10-24 04:30Z","local":"2026-10-24 07:30+03:00"}},
              "arrival":{"airport":{"icao":"EHAM","iata":"AMS","name":"Amsterdam Schiphol","municipalityName":"Amsterdam",
                  "location":{"lat":52.3086,"lon":4.763889},"countryCode":"NL","timeZone":"Europe/Amsterdam"},
                "scheduledTime":{"utc":"2026-10-24 07:00Z","local":"2026-10-24 09:00+02:00"}},
              "lastUpdatedUtc":"2026-06-02 08:13Z","number":"BT 857","status":"Expected",
              "aircraft":{"model":"Airbus A220-300"},"airline":{"name":"airBaltic"}}]
            """;

    static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    static final class Records implements FlightRecordRepository {
        final List<FlightRecord> rows = new ArrayList<>();

        @Override
        public Optional<FlightRecord> find(String number, LocalDate date) {
            return rows.stream().filter(r -> r.flightNumber().equals(number) && r.departureDate().equals(date)).findFirst();
        }

        @Override
        public void save(FlightRecord record) {
            rows.removeIf(r -> r.flightNumber().equals(record.flightNumber()) && r.departureDate().equals(record.departureDate()));
            rows.add(record);
        }

        @Override
        public List<FlightRecord> findAll() {
            return List.copyOf(rows);
        }
    }

    static final class Codeshares implements CodeshareRepository {
        final Map<String, CodeshareMapping> rows = new HashMap<>();

        @Override
        public Optional<String> operatingFor(String booked) {
            return Optional.ofNullable(rows.get(booked)).map(CodeshareMapping::operatingNumber);
        }

        @Override
        public void save(CodeshareMapping mapping) {
            rows.put(mapping.bookedNumber(), mapping);
        }

        @Override
        public List<CodeshareMapping> findAll() {
            return List.copyOf(rows.values());
        }
    }

    /** Counts acquisitions per service; enforces the cap like the real stores. */
    static final class Usage implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service, 0);
            if (now >= cap) return false;
            counts.put(service, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service, 0);
        }
    }
}
```

```java
// FlightDataTest.java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static com.josephinealinea.planner.flights.api.FlightFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FlightDataTest {

    private final FlightProperties props = new FlightProperties(
            new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
            null, null, null, null, null);
    private final Records records = new Records();
    private final Usage usage = new Usage();

    private FlightData data(CannedHttp http, Clock clock) {
        AeroDataBoxClient client = new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage));
        return new FlightData(client, records, new FlightFreshness(props), clock);
    }

    @Test
    void aMissingRecordIsFetchedAndStored() {
        CannedHttp http = new CannedHttp().ok(BT857);

        FlightData.Result result = data(http, at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.stale()).isFalse();
        assertThat(http.callCount()).isEqualTo(1);
        assertThat(records.find("BT857", DAY)).isPresent();
        assertThat(records.find("BT857", DAY).orElseThrow().scheduleFetchedAt())
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void aFreshRecordIsServedWithoutACall() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp second = new CannedHttp();

        // 23 days out is the 72 h tier: an hour later it is still fresh.
        FlightData.Result result = data(second, at("2026-10-01T11:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(second.callCount()).isZero();
    }

    @Test
    void aStaleRecordIsRefetchedAndReplaced() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp second = new CannedHttp().ok(BT857);

        // 73 h later: past the 72 h far-ahead TTL.
        data(second, at("2026-10-04T11:00:00Z")).schedule("BT857", DAY, null);

        assertThat(second.callCount()).isEqualTo(1);
        assertThat(records.find("BT857", DAY).orElseThrow().scheduleFetchedAt())
                .isEqualTo(Instant.parse("2026-10-04T11:00:00Z"));
    }

    @Test
    void aLandedRecordIsNeverRefetched() {
        String arrived = BT857.replace("\"status\":\"Expected\"", "\"status\":\"Arrived\"");
        data(new CannedHttp().ok(arrived), at("2026-10-24T08:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp later = new CannedHttp();

        FlightData.Result result = data(later, at("2026-12-01T00:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(later.callCount()).isZero();
    }

    @Test
    void aGenuineEmptyAnswerIsStoredAsAMissAndNotAskedAgain() {
        CannedHttp first = new CannedHttp().ok("");
        assertThat(data(first, at("2026-10-01T10:00:00Z")).schedule("KL2842", DAY, null).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);

        CannedHttp second = new CannedHttp();
        assertThat(data(second, at("2026-10-01T12:00:00Z")).schedule("KL2842", DAY, null).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(second.callCount()).as("within the negative TTL").isZero();
        assertThat(records.find("KL2842", DAY).orElseThrow().notFound()).isTrue();
    }

    @Test
    void aMissExpiresAfterTheNegativeTtl() {
        data(new CannedHttp().ok(""), at("2026-10-01T10:00:00Z")).schedule("KL2842", DAY, null);
        CannedHttp again = new CannedHttp().ok(BT857.replace("BT 857", "KL 2842"));

        // The default negative TTL is 6 h; 7 h later it is asked again.
        FlightData.Result result = data(again, at("2026-10-01T17:00:00Z")).schedule("KL2842", DAY, null);

        assertThat(again.callCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
    }

    /** Review focus 6: a 500 is an outage, and an outage must never look like "no such flight". */
    @Test
    void anOutageIsNeverStoredAsAMiss() {
        FlightData.Result result = data(new CannedHttp().status(500, "boom"), at("2026-10-01T10:00:00Z"))
                .schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void anOutageServesTheStaleRecordWhenThereIsOne() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);

        FlightData.Result result = data(new CannedHttp().status(500, "boom"), at("2026-10-04T11:00:00Z"))
                .schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.stale()).isTrue();
        assertThat(result.record().scheduleFetchedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }
}
```

- [ ] **Step 5: Write `FlightData`**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The one door to AeroDataBox data: cache first, then the service. The form
 * lookup, the public refresh and the nightly job all come through here, so
 * "how fresh is fresh" and "what counts as a miss" exist once.
 *
 * <b>Only a genuine empty answer is stored as a miss.</b> An outage, a cap or a
 * timeout is UNAVAILABLE and stores nothing; when a stale record exists it is
 * served with {@code stale} set, never blank.
 */
@Service
public class FlightData {

    /** {@code record} is null unless status is FOUND, or a stale one was served. */
    public record Result(Fetch.Status status, FlightRecord record, boolean stale) {}

    private final AeroDataBoxClient client;
    private final FlightRecordRepository records;
    private final FlightFreshness rules;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightData(AeroDataBoxClient client, FlightRecordRepository records, FlightFreshness rules) {
        this(client, records, rules, Clock.systemUTC());
    }

    FlightData(AeroDataBoxClient client, FlightRecordRepository records, FlightFreshness rules, Clock clock) {
        this.client = client;
        this.records = records;
        this.rules = rules;
        this.clock = clock;
    }

    public Result schedule(String number, LocalDate date, Instant departureHint) {
        Instant now = clock.instant();
        Optional<FlightRecord> held = records.find(number, date);

        if (held.isPresent()) {
            FlightRecord record = held.get();
            if (record.notFound()) {
                if (rules.negativeFresh(record, now)) return new Result(Fetch.Status.NOT_FOUND, null, false);
            } else if (rules.scheduleFresh(record, now, departureHint)) {
                return new Result(Fetch.Status.FOUND, record, false);
            }
        }

        Fetch<com.josephinealinea.planner.flights.domain.FlightSchedule> fetched = client.flight(number, date);
        switch (fetched.status()) {
            case FOUND -> {
                FlightRecord base = held.filter(r -> !r.notFound())
                        .orElse(new FlightRecord(number, date, null, null, null, null, false));
                FlightRecord updated = base.withSchedule(fetched.value(), now);
                records.save(updated);
                return new Result(Fetch.Status.FOUND, updated, false);
            }
            case NOT_FOUND -> {
                records.save(FlightRecord.missing(number, date, now));
                return new Result(Fetch.Status.NOT_FOUND, null, false);
            }
            default -> {
                // Unavailable: store nothing. Serve what we have, marked stale.
                return held.filter(r -> !r.notFound() && r.schedule() != null)
                        .map(r -> new Result(Fetch.Status.FOUND, r, true))
                        .orElse(new Result(Fetch.Status.UNAVAILABLE, null, false));
            }
        }
    }
}
```

(Replace the fully qualified `FlightSchedule` in the `Fetch<...>` declaration with a normal import.)

- [ ] **Step 6: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'FlightFreshnessTest' --tests 'FlightDataTest'`
Expected: PASS.

- [ ] **Step 7: Checkpoint.**

---

### Task 9: The form lookup and its endpoint

**Files:**
- Create: `flights/api/FlightLookup.java`, `flights/web/FlightController.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/api/FlightLookupTest.java`, `flights/web/FlightControllerTest.java`

**Interfaces:**
- Consumes: `FlightData.schedule`, `AviationStackClient.codeshareOf`, `CodeshareRepository`, `FlightNumbers`, `FlightSnapshot`, `TripAccessService.requireMember`, `CurrentUserContext`.
- Produces:
  - `FlightLookup.lookup(String rawNumber, LocalDate date): Outcome` with `record Outcome(Fetch.Status status, String operatingNumber, FlightSchedule schedule)`; `FlightLookup.snapshotOf(String booked, String operating, FlightSchedule schedule): FlightSnapshot`
  - `GET /api/v1/trips/{tripId}/flights/lookup?number=&date=` returning `LookupResponse(String status, String operatingNumber, String departureTime, String arrivalDate, String arrivalTime, FlightSnapshot flight)`

- [ ] **Step 1: Write the failing `FlightLookupTest`.** Build `FlightLookup` over real `FlightData`, `AviationStackClient` and `AeroDataBoxClient` backed by two `CannedHttp` scripts and the in-memory repositories from Task 8, plus an in-memory `CodeshareRepository`. Cases:

```java
    @Test void aDirectHitFillsFromAeroDataBoxAndSpendsNoAviationStackCall()
    /** The KL2842 story. */
    @Test void aCodeshareIsResolvedThenLookedUpAsItsOperatingFlight()
        // aero: "" (miss for KL2842), stack: KL2842 payload (codeshared bt857), aero: BT857 payload
        // -> FOUND, operatingNumber "BT857", mapping saved, aero callCount 2, stack callCount 1
    @Test void aStoredMappingSkipsAviationStack()
        // mapping KL2842->BT857 present: aero asked once for BT857, stack callCount 0
    @Test void aMissOnBothIsNotFoundAndTheBookedNumberMissIsCached()
    /** Review focus 6. */
    @Test void anAviationStackOutageAfterAMissIsUnavailableNotNotFound()
    @Test void anAeroDataBoxOutageIsUnavailable()
    /** Review focus 1. */
    @Test void lowerCaseAndSpacesAreTheSameFlight()   // lookup("kl 2842") behaves as "KL2842", mapping keyed "KL2842"
    @Test void aMalformedNumberIsRefusedBeforeAnyCall()  // throws ApiException error.flight.numberInvalid, 0 calls
    @Test void aCodeshareThatResolvesToItselfIsIgnored() // stack says operating == booked -> NOT_FOUND, no mapping
    @Test void snapshotCarriesAirportsTerminalsAndBothNumbers()
```

- [ ] **Step 2: Write `FlightLookup`**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightNumbers;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.shared.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

/**
 * The lookup behind the form's icon: what flight is this, on this date?
 *
 * Order: the number as booked (or its known operating number) against
 * AeroDataBox; on a genuine miss, AviationStack once to learn whether it is a
 * codeshare; then AeroDataBox again for the operating number. The pairing is
 * remembered for good, so a number costs one AviationStack call ever.
 *
 * Best effort by design. AviationStack sees only flights around today, so a
 * weekly flight that is not operating this week may not resolve.
 */
@Service
public class FlightLookup {

    public record Outcome(Fetch.Status status, String operatingNumber, FlightSchedule schedule) {}

    private final FlightData data;
    private final AviationStackClient stack;
    private final CodeshareRepository codeshares;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightLookup(FlightData data, AviationStackClient stack, CodeshareRepository codeshares) {
        this(data, stack, codeshares, Clock.systemUTC());
    }

    FlightLookup(FlightData data, AviationStackClient stack, CodeshareRepository codeshares, Clock clock) {
        this.data = data;
        this.stack = stack;
        this.codeshares = codeshares;
        this.clock = clock;
    }

    public Outcome lookup(String rawNumber, LocalDate date) {
        String booked = FlightNumbers.normalise(rawNumber);
        if (!FlightNumbers.valid(booked)) throw ApiException.badRequest("error.flight.numberInvalid");

        Optional<String> known = codeshares.operatingFor(booked);
        String target = known.orElse(booked);
        FlightData.Result result = data.schedule(target, date, null);

        if (result.status() == Fetch.Status.NOT_FOUND && known.isEmpty()) {
            Fetch<String> codeshare = stack.codeshareOf(booked);
            if (codeshare.status() == Fetch.Status.UNAVAILABLE) {
                // We could not finish the question, so we cannot say "not found".
                return new Outcome(Fetch.Status.UNAVAILABLE, null, null);
            }
            if (codeshare.status() == Fetch.Status.FOUND && !codeshare.value().equals(booked)) {
                target = codeshare.value();
                result = data.schedule(target, date, null);
                if (result.status() == Fetch.Status.FOUND) {
                    codeshares.save(new CodeshareMapping(booked, target, clock.instant()));
                }
            }
        }

        if (result.status() != Fetch.Status.FOUND) return new Outcome(result.status(), null, null);
        String operating = target.equals(booked) ? null : target;
        return new Outcome(Fetch.Status.FOUND, operating, result.record().schedule());
    }

    /** What the entry keeps: both numbers, both airports, both terminals. */
    public static FlightSnapshot snapshotOf(String booked, String operating, FlightSchedule schedule) {
        return new FlightSnapshot(FlightNumbers.normalise(booked), operating,
                schedule.departure().airport(), schedule.arrival().airport(),
                schedule.departure().terminal(), schedule.arrival().terminal());
    }
}
```

(One decision to state in a code comment above the mapping save: it is saved only when the operating flight is actually found, so a wrong pairing is never remembered.)

- [ ] **Step 3: Run `FlightLookupTest`** — Expected PASS.

- [ ] **Step 4: Write the failing controller test.** Use the style of an existing controller test (`ls src/test/java/com/josephinealinea/planner/**/web/*ControllerTest.java`; if the project has none, test through a `MockMvc` standalone setup with the controller built by hand). Cases: 200 with `status: "found"` and `arrivalDate` equal to the **arrival's** local date for an overnight flight (review focus 2: a fixture departing 2026-10-24T23:30+03:00 and arriving 2026-10-25T01:10+02:00 gives `arrivalDate` `2026-10-25`, `arrivalTime` `01:10`, `departureTime` `23:30`); `notFound` and `unavailable` map to those strings; a non-member gets 404; a malformed number gets 400.

- [ ] **Step 5: Write `FlightController`**

```java
package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.api.FlightLookup;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.trips.api.TripAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * The form's lookup icon. Members only: a lookup spends the install's free-tier
 * quota. Read-only, and it never writes to the itinerary: the form fills its own
 * fields and the member saves them.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/flights")
public class FlightController {

    /**
     * {@code status} is {@code found}, {@code notFound} or {@code unavailable}.
     * Times are airport wall-clock (HH:mm), and the arrival has its own date
     * because an overnight or date-line flight lands on another day.
     */
    public record LookupResponse(String status, String operatingNumber, String departureTime,
                                 String arrivalDate, String arrivalTime, FlightSnapshot flight) {}

    private final FlightLookup lookup;
    private final TripAccessService access;
    private final CurrentUserContext currentUser;

    public FlightController(FlightLookup lookup, TripAccessService access, CurrentUserContext currentUser) {
        this.lookup = lookup;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/lookup")
    LookupResponse lookup(@PathVariable String tripId,
                          @RequestParam String number,
                          @RequestParam LocalDate date) {
        access.requireMember(tripId, currentUser.userId());
        FlightLookup.Outcome outcome = lookup.lookup(number, date);

        if (outcome.status() != Fetch.Status.FOUND) {
            String status = outcome.status() == Fetch.Status.NOT_FOUND ? "notFound" : "unavailable";
            return new LookupResponse(status, null, null, null, null, null);
        }
        FlightSchedule schedule = outcome.schedule();
        LocalDateTime departs = localOf(schedule.departure().scheduledLocal());
        LocalDateTime arrives = localOf(schedule.arrival().scheduledLocal());
        return new LookupResponse("found", outcome.operatingNumber(),
                departs == null ? null : departs.toLocalTime().toString(),
                arrives == null ? null : arrives.toLocalDate().toString(),
                arrives == null ? null : arrives.toLocalTime().toString(),
                FlightLookup.snapshotOf(number, outcome.operatingNumber(), schedule));
    }

    /** The wall-clock part of {@code 2026-10-24T07:30+03:00}, dropping the offset. */
    private static LocalDateTime localOf(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso).toLocalDateTime();
    }
}
```

`toLocalTime().toString()` yields `07:30`; `OffsetDateTime.toLocalDateTime()` keeps the wall-clock of the stated offset, which is exactly the airport's local time.

- [ ] **Step 6: Run to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'FlightLookupTest' --tests 'FlightControllerTest'`
Expected: PASS.

- [ ] **Step 7: Regenerate the OpenAPI doc** (CLAUDE.md requires it in the same change).

Run: `cd planner-api && ./gradlew openApiUpdate` then `./gradlew test --tests 'OpenApiDocTest'`
Expected: `docs/api/openapi.yaml` gains `/api/v1/trips/{tripId}/flights/lookup` and the entry's `flight`; the test PASSES.

- [ ] **Step 8: Checkpoint.**

---

### Task 10: The public refresh endpoint

**Files:**
- Modify: `trips/infra/TripRepository.java`, `YamlTripRepository.java`, `JdbcTripRepository.java`, `config/SecurityConfig.java`
- Create: `flights/api/FlightStatusService.java`, `flights/web/PublicFlightController.java`
- Test: `src/test/java/com/josephinealinea/planner/flights/api/FlightStatusServiceTest.java`, `flights/web/PublicFlightControllerTest.java`, and a `findAllPublished` case in the trip repository contract

**Interfaces:**
- Consumes: `FlightData`, `AviationStackClient.live`, `FlightFreshness`, `FlightRecordRepository`, `CodeshareRepository`, `ItineraryRepository.findAll`, `TripRepository.findBySlug`.
- Produces:
  - `TripRepository.findAllPublished(): List<Trip>`
  - `FlightStatusService.status(String slug, String rawNumber, LocalDate date): Optional<View>` with `record View(String number, String operatingNumber, FlightSchedule schedule, FlightLive live, Instant scheduleFetchedAt, Instant liveFetchedAt, boolean stale, long ttlSeconds)`
  - `GET /api/v1/public/trips/{slug}/flights?number=&date=` → 200 `View`, 204 when there is nothing to show, 404 when the flight is not on that published trip

- [ ] **Step 1: Write the failing repository test.** In the trip repository contract (find it with `ls src/test/java/com/josephinealinea/planner/trips/`; it is the shared abstract test both stores extend) add:

```java
    @Test
    void findAllPublishedIsExactlyThePublishedTrips() {
        Trip draft = trip("draft-trip", TripStatus.DRAFT);
        Trip live = trip("live-trip", TripStatus.PUBLISHED);
        store().save(draft);
        store().save(live);

        assertThat(store().findAllPublished()).extracting(Trip::getSlug).contains("live-trip").doesNotContain("draft-trip");
    }
```

using whatever helper that contract already uses to build a saved trip (reuse it, setting `status`).

- [ ] **Step 2: Implement `findAllPublished`.**

Interface:

```java
    /** Every published trip. The nightly flight job is the only reader. */
    List<Trip> findAllPublished();
```

`YamlTripRepository`:

```java
    @Override
    public List<Trip> findAllPublished() {
        return index().stream()
                .map(entry -> loadBySlug(entry.slug()))
                .flatMap(Optional::stream)
                .filter(trip -> trip.getStatus() == TripStatus.PUBLISHED)
                .toList();
    }
```

`JdbcTripRepository` (mirroring `findAllForUser`, whose exact `withChildren(...)` call you copy from the method above it):

```java
    @Override
    public List<Trip> findAllPublished() {
        return transactions.execute(status -> withChildren(jdbc.sql("""
                        SELECT t.* FROM trips t
                        WHERE t.status = 'PUBLISHED'
                        ORDER BY t.created_at, t.id
                        """)
                .query(TRIP_ROW)     // use the same row mapper findAllForUser passes
                .list()));
    }
```

Run: `cd planner-api && ./gradlew test --tests '*TripRepository*'` — Expected PASS on both stores.

- [ ] **Step 3: Permit the public GET** in `SecurityConfig`, right after the `GET /api/v1/config` line:

```java
                .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()
```

- [ ] **Step 4: Write the failing `FlightStatusServiceTest`.** Build the service over in-memory repositories, a scripted `CannedHttp` for each client and a fixed `Clock`. Cases:

```java
    @Test void aFlightOnAPublishedTripReturnsItsScheduleAndTheFetchTime()
    @Test void aFlightOnADraftTripIsNotFound()                       // ApiException notFound error.flight.notFound
    @Test void aFlightNotOnThatTripIsNotFound()                      // number unknown
    /** Review focus 5. */
    @Test void anEntryWhoseDateWasEditedIsNotFoundForTheOldDate()
    @Test void theBookedOrTheOperatingNumberBothFindTheEntry()       // KL2842 and BT857
    @Test void theOperatingNumberComesFromTheEntryThenTheMapThenTheBookedNumber()
    @Test void aRefreshNeverCallsAviationStackToResolveACodeshare()  // stack callCount 0 outside the live window
    @Test void outsideTheLiveWindowOnlyAeroDataBoxIsCalled()
    @Test void insideTheWindowLiveExtrasAreFetchedAndStoredOnTheRecord()
    @Test void freshLiveExtrasAreNotRefetched()
    @Test void aLandedFlightCallsNothing()
    @Test void whenAviationStackFailsTheScheduleIsStillReturned()
    @Test void nothingAvailableAndNothingCachedIsAnEmptyOptional()
    @Test void ttlSecondsIsTheShorterOfTheScheduleAndLiveTtlWhileLive()
```

- [ ] **Step 5: Write `FlightStatusService`**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightNumbers;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * What a reader gets when they press refresh on a published flight.
 *
 * <b>Never an open proxy.</b> The flight must be on the entries of a
 * <i>published</i> trip, matched by number (booked or operating) and local
 * start date; anything else is a 404. It returns flight status only, never the
 * entry.
 *
 * <b>Which calls.</b> Refresh on read: AeroDataBox when the record is missing or
 * past its tier TTL. AviationStack is added only from the window before
 * departure until landing, when its own 15 minutes has lapsed, and never to
 * resolve a codeshare (that is the entry form's job), so a reader's click cannot
 * spend the scarce quota on resolution.
 */
@Service
public class FlightStatusService {

    public record View(String number, String operatingNumber, FlightSchedule schedule, FlightLive live,
                       Instant scheduleFetchedAt, Instant liveFetchedAt, boolean stale, long ttlSeconds) {}

    private final TripRepository trips;
    private final ItineraryRepository itinerary;
    private final CodeshareRepository codeshares;
    private final FlightData data;
    private final AviationStackClient stack;
    private final FlightRecordRepository records;
    private final FlightFreshness rules;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightStatusService(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                               FlightData data, AviationStackClient stack, FlightRecordRepository records,
                               FlightFreshness rules) {
        this(trips, itinerary, codeshares, data, stack, records, rules, Clock.systemUTC());
    }

    FlightStatusService(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                        FlightData data, AviationStackClient stack, FlightRecordRepository records,
                        FlightFreshness rules, Clock clock) {
        this.trips = trips;
        this.itinerary = itinerary;
        this.codeshares = codeshares;
        this.data = data;
        this.stack = stack;
        this.records = records;
        this.rules = rules;
        this.clock = clock;
    }

    /** Empty when there is nothing to show yet (the page keeps what it published). */
    public Optional<View> status(String slug, String rawNumber, LocalDate date) {
        Trip trip = trips.findBySlug(Slugs.requireSafe(slug))
                .filter(t -> t.getStatus() == TripStatus.PUBLISHED)
                .orElseThrow(() -> ApiException.notFound("error.flight.notFound"));
        String number = FlightNumbers.normalise(rawNumber);
        ItineraryItem entry = itinerary.findAll(trip.getSlug()).stream()
                .filter(e -> onDate(e, date) && hasNumber(e.getFlight(), number))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("error.flight.notFound"));

        FlightSnapshot flight = entry.getFlight();
        String booked = flight.number();
        String operating = flight.operatingNumber() != null
                ? flight.operatingNumber()
                : codeshares.operatingFor(booked).orElse(booked);

        Instant now = clock.instant();
        FlightData.Result result = data.schedule(operating, date, hintOf(entry));
        if (result.record() == null) return Optional.empty();

        FlightRecord record = result.record();
        Instant departure = FlightFreshness.departureOf(record.schedule());
        boolean over = rules.landed(record.schedule(), now);
        if (!over && rules.inLiveWindow(now, departure) && !rules.liveFresh(record, now)) {
            Fetch<FlightLive> live = stack.live(booked, date);
            if (live.status() == Fetch.Status.FOUND) {
                record = record.withLive(live.value(), now);
                records.save(record);
            }
        }

        Duration ttl = rules.scheduleTtl(now, departure);
        if (!over && rules.inLiveWindow(now, departure)) ttl = ttl.compareTo(liveTtl()) < 0 ? ttl : liveTtl();
        return Optional.of(new View(booked, flight.operatingNumber() != null ? flight.operatingNumber()
                : (operating.equals(booked) ? null : operating),
                record.schedule(), record.live(), record.scheduleFetchedAt(), record.liveFetchedAt(),
                result.stale(), ttl.toSeconds()));
    }

    private Duration liveTtl() {
        return rules.liveTtl();
    }

    private static boolean onDate(ItineraryItem e, LocalDate date) {
        return e.getStartAt() != null && e.getStartAt().toLocalDate().equals(date);
    }

    private static boolean hasNumber(FlightSnapshot flight, String number) {
        return flight != null && number != null
                && (number.equals(flight.number()) || number.equals(flight.operatingNumber()));
    }

    /** When the entry says it departs, in the origin's timezone when the snapshot knows it. */
    private static Instant hintOf(ItineraryItem entry) {
        if (entry.getStartAt() == null) return null;
        String zone = entry.getFlight().from() == null ? null : entry.getFlight().from().timezone();
        return entry.getStartAt().atZone(zone == null ? ZoneId.of("UTC") : ZoneId.of(zone)).toInstant();
    }
}
```

Add the one accessor the service uses to `FlightFreshness`:

```java
    public Duration liveTtl() {
        return props.live().ttl();
    }
```

- [ ] **Step 6: Write `PublicFlightController`**

```java
package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.flights.api.FlightStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The refresh icon on a published page. No sign-in: a published page has none.
 * Reaches the API through the existing {@code /api/*} forwarding Function, on the
 * page's own origin. {@code no-cache}: the page caches the reply itself for the
 * TTL the body carries, so the HTTP cache must not add a second, hidden clock.
 */
@RestController
@RequestMapping("/api/v1/public/trips/{slug}/flights")
public class PublicFlightController {

    private final FlightStatusService status;

    public PublicFlightController(FlightStatusService status) {
        this.status = status;
    }

    @GetMapping
    ResponseEntity<FlightStatusService.View> flight(@PathVariable String slug,
                                                    @RequestParam String number,
                                                    @RequestParam LocalDate date) {
        return status.status(slug, number, date)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(view))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noCache()).build());
    }
}
```

- [ ] **Step 7: Write the controller test** (`PublicFlightControllerTest`): unauthenticated request returns 200 for a published flight; 404 for a draft trip; 204 when nothing is available; `Cache-Control: no-cache` present. Use the same web-test style as Task 9.

- [ ] **Step 8: Run**

Run: `cd planner-api && ./gradlew test --tests 'FlightStatusServiceTest' --tests 'PublicFlightControllerTest' --tests '*TripRepository*' --tests 'OpenApiDocTest'`
Expected: PASS (regenerate the OpenAPI doc with `./gradlew openApiUpdate` first; it gains the public path).

- [ ] **Step 9: Checkpoint.**

---

### Task 11: The nightly prewarm job

**Files:**
- Create: `flights/api/FlightPrewarmJob.java`, `docs/scheduled/flights-prewarm.md`
- Test: `src/test/java/com/josephinealinea/planner/flights/api/FlightPrewarmJobTest.java`

**Interfaces:**
- Consumes: `TripRepository.findAllPublished`, `ItineraryRepository.findAll`, `CodeshareRepository`, `FlightData.schedule`, `ApiUsageService.calls`, `FlightProperties`.
- Produces: `FlightPrewarmJob.prewarm(Instant now): int` (number of flights processed), `@Scheduled run()`.

- [ ] **Step 1: Write the failing test.** Cases (in-memory repositories, `CannedHttp` for AeroDataBox, fixed clock):

```java
    @Test void onlyPublishedTripsAreWarmed()                         // a draft trip's flight causes 0 calls
    @Test void onlyFlightsWithinSeventyTwoHoursAreWarmed()           // a flight 4 days out is skipped; 71 h is warmed
    @Test void theSameFlightOnTwoTripsIsOneCall()                    // dedupe by operating number + date
    @Test void aFlightWithAFreshRecordCostsNoCall()
    @Test void soonestDepartureIsWarmedFirst()                       // with the cap allowing only one, the sooner one wins
    @Test void itStopsAtTheAeroDataBoxCap()
    @Test void aCodeshareEntryIsWarmedUnderItsOperatingNumber()
    @Test void anEntryWithNoFlightIsIgnored()
    @Test void aFailureOnOneFlightDoesNotStopTheRest()
```

- [ ] **Step 2: Write the job**

```java
package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warms the cache once a night for flights leaving within 72 hours, so the first
 * reader to press refresh does not wait on a call.
 *
 * <b>Never the only path.</b> On free-tier Cloud Run the CPU exists only while a
 * request runs, so this cron may never fire; refresh-on-read in
 * {@link FlightData} is the real mechanism and this only means fewer first
 * clicks wait. A failed run leaves nothing worse than a cold cache.
 *
 * Published trips only (nobody sees flight status otherwise), deduped by
 * operating number and date, soonest first, stopping at the AeroDataBox cap.
 * AviationStack is never called here: it is click-only, inside its window.
 */
@Component
public class FlightPrewarmJob {

    private static final Logger log = LoggerFactory.getLogger(FlightPrewarmJob.class);
    private static final Duration HORIZON = Duration.ofHours(72);

    private record Candidate(String number, LocalDate date, Instant departure) {}

    private final TripRepository trips;
    private final ItineraryRepository itinerary;
    private final CodeshareRepository codeshares;
    private final FlightData data;
    private final ApiUsageService usage;
    private final FlightProperties props;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightPrewarmJob(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                            FlightData data, ApiUsageService usage, FlightProperties props) {
        this(trips, itinerary, codeshares, data, usage, props, Clock.systemUTC());
    }

    FlightPrewarmJob(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                     FlightData data, ApiUsageService usage, FlightProperties props, Clock clock) {
        this.trips = trips;
        this.itinerary = itinerary;
        this.codeshares = codeshares;
        this.data = data;
        this.usage = usage;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.flights.prewarm.cron:0 0 1 * * *}", zone = "UTC")
    public void run() {
        log.info("Warming flight cache");
        int warmed = prewarm(clock.instant());
        log.info("Flight cache warmed for {} flights", warmed);
    }

    /** Returns how many flights were looked at. Public for tests. */
    public int prewarm(Instant now) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Trip trip : trips.findAllPublished()) {
            for (ItineraryItem entry : itinerary.findAll(trip.getSlug())) {
                Candidate candidate = candidateOf(entry, now);
                if (candidate != null) unique.putIfAbsent(candidate.number() + "|" + candidate.date(), candidate);
            }
        }
        List<Candidate> soonestFirst = unique.values().stream()
                .sorted(Comparator.comparing(Candidate::departure)).toList();

        int looked = 0;
        for (Candidate candidate : soonestFirst) {
            if (usage.calls(AeroDataBoxClient.SERVICE) >= props.aerodatabox().cap()) {
                log.info("AeroDataBox cap reached; stopping the prewarm");
                break;
            }
            try {
                data.schedule(candidate.number(), candidate.date(), candidate.departure());
                looked++;
            } catch (Exception e) {
                log.warn("Prewarm of {} on {} failed: {}", candidate.number(), candidate.date(), e.getMessage());
            }
        }
        return looked;
    }

    private Candidate candidateOf(ItineraryItem entry, Instant now) {
        FlightSnapshot flight = entry.getFlight();
        if (flight == null || flight.number() == null || entry.getStartAt() == null) return null;
        String zone = flight.from() == null ? null : flight.from().timezone();
        Instant departure = entry.getStartAt().atZone(zone == null ? ZoneId.of("UTC") : ZoneId.of(zone)).toInstant();
        if (departure.isBefore(now) || departure.isAfter(now.plus(HORIZON))) return null;
        String number = flight.operatingNumber() != null
                ? flight.operatingNumber()
                : codeshares.operatingFor(flight.number()).orElse(flight.number());
        return new Candidate(number, entry.getStartAt().toLocalDate(), departure);
    }
}
```

`@EnableScheduling` is already on `RatesConfig`, so no new enabling is needed.

- [ ] **Step 3: Write `docs/scheduled/flights-prewarm.md`** (CLAUDE.md requires it in the same change). Contents, all required fields:

```markdown
# Flights prewarm

Warms the flight cache once a night so the first reader to refresh a flight on a
published page does not wait on an outside call.

## Schedule
Every day at **01:00 UTC**.

## Configuration
- Property `app.flights.prewarm.cron`, environment variable `FLIGHTS_PREWARM_CRON`.
- Default `0 0 1 * * *` lives in `application.yml` and in `FlightProperties.Prewarm`.
- The cron zone is fixed to UTC in the annotation.

## What it does
1. Lists every published trip (`TripRepository.findAllPublished`).
2. Takes each itinerary entry that has a flight and departs within 72 hours.
3. Dedupes by operating flight number and departure date, so a flight on three trips is one call.
4. Sorts soonest first and, for each, asks `FlightData.schedule`, which skips a fresh record.
5. Stops when the AeroDataBox monthly cap is reached.

AviationStack is never called: it is click-only, inside its two-hour window.

## Classes
- `FlightPrewarmJob`: the cron and the selection.
- `FlightData`: the cache-aware AeroDataBox access; decides whether a call is needed.
- `FlightFreshness`: the TTL tiers (72 h, 24 h, 1 h) and the landed rule.
- `ApiUsageService`: counts calls against the monthly cap.

## Failure behaviour
A failure on one flight is logged and the rest continue. A failed run leaves a cold
cache and nothing worse: nothing depends on it having run.

## Scale-to-zero on Cloud Run
The CPU exists only while a request runs, so this cron may never fire. **Refresh on
read is the real mechanism**: a reader's click refetches when the record is missing
or past its TTL. The job only means fewer first clicks wait for a call. There is no
monthly reset job either: the usage counter is keyed by month, so a new month
starts at zero by itself.
```

- [ ] **Step 4: Run**

Run: `cd planner-api && ./gradlew test --tests 'FlightPrewarmJobTest'`
Expected: PASS.

- [ ] **Step 5: Checkpoint** — `./gradlew test --tests 'YamlModeStartupTest'` passes (the job is a bean that boots).

---

### Task 12: The published page

**Files:**
- Modify: `publish/api/PublishedTrip.java`, `publish/api/StaticSiteRenderer.java`, `planner-api/src/main/resources/publish/page.js`, `page.css`, `messages_en.properties`
- Test: `src/test/java/com/josephinealinea/planner/publish/PublishedFlightTest.java`; extend `PersonalPageTest` and the page-strings test

**Interfaces:**
- Consumes: `ItineraryItem.getFlight`, `FlightSnapshot`.
- Produces: `PublishedTrip.Entry` gains a trailing `Flight flight`; `PublishedTrip.Flight(String number, String operatingNumber, String fromIata, String fromCity, String toIata, String toCity, String terminalFrom, String terminalTo)`. Coordinates are deliberately not shipped.

- [ ] **Step 1: Write the failing test** `PublishedFlightTest`, in the style of `PersonalPageTest` (copy how it renders a trip and reads the file): a trip with one transport entry carrying `KL2842`/`BT857`, TLL→AMS, latitude 59.4133. Assert:

```java
    @Test void theFlightNumbersAndAirportsAreInThePublishedFile()      // "KL2842", "BT857", "TLL", "Tallinn"
    @Test void coordinatesAreNotShipped()                              // file does not contain "59.4133"
    @Test void aNumberOnlyFlightShipsWithNoAirports()
    @Test void anEntryWithNoFlightHasNoFlightKey()
    /** The rule that "not displayed" must mean "not shipped". */
    @Test void anotherMembersFlightIsNotOnMyPersonalPage()             // Travellers narrowing: file lacks "KL2842"
    @Test void noKeyOrProviderFieldIsInTheFile()                       // no "access_key", no "X-RapidAPI", no gate/baggage
```

- [ ] **Step 2: Add the record** to `PublishedTrip` and the trailing component to `Entry`, keeping the old 8-argument shape so existing callers and tests compile:

```java
    public record Entry(String category,
                        String categoryLabel,
                        String icon,
                        String description,
                        String startTime,
                        String endTime,
                        String cost,
                        String currency,
                        Flight flight) {

        /** The shape from before flights existed. */
        public Entry(String category, String categoryLabel, String icon, String description,
                     String startTime, String endTime, String cost, String currency) {
            this(category, categoryLabel, icon, description, startTime, endTime, cost, currency, null);
        }
    }

    /**
     * What a published flight card can draw without a lookup. Coordinates and
     * country are left out on purpose: the page never draws them, and a public
     * file gets what it needs to draw itself.
     */
    public record Flight(String number, String operatingNumber,
                         String fromIata, String fromCity, String toIata, String toCity,
                         String terminalFrom, String terminalTo) {}
```

- [ ] **Step 3: Fill it in `StaticSiteRenderer.entryFor`** (the constructor call at the end):

```java
                showItineraryCost ? item.getCurrency() : null,
                flightOf(item));
```

```java
    /** Only the plan's own entry carries a flight, and only transport ever has one. */
    private static PublishedTrip.Flight flightOf(ItineraryItem item) {
        FlightSnapshot flight = item.getFlight();
        if (flight == null) return null;
        var from = flight.from();
        var to = flight.to();
        return new PublishedTrip.Flight(flight.number(), flight.operatingNumber(),
                from == null ? null : from.iata(), from == null ? null : from.city(),
                to == null ? null : to.iata(), to == null ? null : to.city(),
                flight.terminalFrom(), flight.terminalTo());
    }
```

(Import `com.josephinealinea.planner.flights.domain.FlightSnapshot`.) The personal-page narrowing already filters entries by `Travellers.includes` before `entryFor`, so nothing else changes.

- [ ] **Step 4: Add the page strings** to `messages_en.properties` (only `page.*` keys ship; `page.*` messages are never formatted so a single `'` is fine):

```properties
page.flight.refresh=Refresh flight status
page.flight.updated=Updated {time}
page.flight.unavailable=Live status is not available right now
page.flight.operatedAs=operated as {number}
page.flight.gate=Gate {gate}
page.flight.baggage=Belt {belt}
page.flight.delayed=Delayed {minutes} min
page.flight.onTime=On time
page.flight.terminal=Terminal {terminal}
page.flight.status.Expected=Scheduled
page.flight.status.CheckIn=Check-in open
page.flight.status.Boarding=Boarding
page.flight.status.GateClosed=Gate closed
page.flight.status.Departed=Departed
page.flight.status.EnRoute=En route
page.flight.status.Delayed=Delayed
page.flight.status.Approaching=Approaching
page.flight.status.Arrived=Arrived
page.flight.status.Canceled=Cancelled
page.flight.status.Diverted=Diverted
```

- [ ] **Step 5: Add the refresh code to `page.js`.** Insert the block below just above `function checklistPanel()`, and call `flightBlock(entry, day.date)` in the entry loop (after `body.appendChild(el('div', 'entry-desc', …))` and the `meta` block, before `row.appendChild(body)`):

```js
        if (entry.flight) body.appendChild(flightBlock(entry.flight, day.date));
```

```js
  // ---- flights -----------------------------------------------------------
  // Nothing is fetched on load. A click asks the API, which decides which of its
  // two services to call; the answer is kept in this browser for the TTL the
  // server sent, so reopening the page does not spend a call.

  var FLIGHT_CACHE = 'publishedFlight:';

  function flightSlug() {
    var parts = location.pathname.split('/').filter(Boolean);
    return parts[0] === 'p' ? parts[1] : null;
  }

  function readFlightCache(key) {
    try {
      var raw = localStorage.getItem(FLIGHT_CACHE + key);
      if (!raw) return null;
      var saved = JSON.parse(raw);
      return saved.expires > Date.now() ? saved.view : null;
    } catch (e) { return null; }
  }

  function writeFlightCache(key, view, ttlSeconds) {
    try {
      localStorage.setItem(FLIGHT_CACHE + key,
        JSON.stringify({ expires: Date.now() + ttlSeconds * 1000, view: view }));
    } catch (e) { /* private mode */ }
  }

  // "14:20", with the date added when it is not today: the time we last fetched.
  function fetchedLabel(iso) {
    var when = new Date(iso);
    var time = when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    return when.toDateString() === new Date().toDateString()
      ? time
      : when.toLocaleDateString([], { day: 'numeric', month: 'short' }) + ' ' + time;
  }

  function hhmm(iso) { return iso ? iso.substring(11, 16) : ''; }

  function drawFlightStatus(box, view) {
    box.textContent = '';
    var lines = [];
    var s = view.schedule;
    if (s) {
      var status = t('page.flight.status.' + s.status);
      lines.push(status.indexOf('page.flight.status.') === 0 ? s.status : status);
      var dep = s.departure || {}, arr = s.arrival || {};
      lines.push(hhmm(dep.revisedLocal || dep.scheduledLocal) + ' → ' + hhmm(arr.predictedLocal || arr.scheduledLocal));
      if (dep.terminal) lines.push(t('page.flight.terminal', { terminal: dep.terminal }));
    }
    var live = view.live;
    if (live) {
      var d = live.departure || {}, a = live.arrival || {};
      if (d.gate) lines.push(t('page.flight.gate', { gate: d.gate }));
      if (a.baggageBelt) lines.push(t('page.flight.baggage', { belt: a.baggageBelt }));
      // 0 is an answer: "on time", not "unknown".
      if (d.delayMinutes != null) {
        lines.push(d.delayMinutes > 0 ? t('page.flight.delayed', { minutes: d.delayMinutes }) : t('page.flight.onTime'));
      }
    }
    lines.forEach(function (line) { box.appendChild(el('div', 'entry-meta', line)); });
    if (view.scheduleFetchedAt) {
      box.appendChild(el('div', 'entry-meta flight-updated',
        t('page.flight.updated', { time: fetchedLabel(view.scheduleFetchedAt) })));
    }
    if (view.liveFetchedAt) {
      box.appendChild(el('div', 'entry-meta flight-updated',
        t('page.flight.updated', { time: fetchedLabel(view.liveFetchedAt) })));
    }
  }

  function flightBlock(flight, date) {
    var wrap = el('div', 'flight');
    var head = el('div', 'entry-meta');
    var label = flight.number + (flight.operatingNumber
      ? ' (' + t('page.flight.operatedAs', { number: flight.operatingNumber }) + ')' : '');
    var route = flight.fromIata && flight.toIata ? ' · ' + flight.fromIata + ' → ' + flight.toIata : '';
    head.appendChild(document.createTextNode(label + route + ' '));

    var button = el('button', 'flight-refresh', '↻');
    button.type = 'button';
    button.setAttribute('aria-label', t('page.flight.refresh'));
    head.appendChild(button);
    wrap.appendChild(head);

    var box = el('div', 'flight-status');
    wrap.appendChild(box);

    var key = flight.number + ':' + date;
    var cached = readFlightCache(key);
    if (cached) drawFlightStatus(box, cached);

    button.addEventListener('click', function () {
      var slug = flightSlug();
      if (!slug) return;
      var hit = readFlightCache(key);
      if (hit) { drawFlightStatus(box, hit); return; }
      button.disabled = true;
      fetch('/api/v1/public/trips/' + encodeURIComponent(slug) + '/flights?number='
            + encodeURIComponent(flight.number) + '&date=' + encodeURIComponent(date))
        .then(function (response) {
          // 204: nothing to show yet. 404: the plan has moved on since this file was
          // written. Either way the card stays as published, without an error.
          if (response.status === 200) return response.json();
          if (response.status === 204 || response.status === 404) return null;
          throw new Error('status ' + response.status);
        })
        .then(function (view) {
          if (!view) return;
          drawFlightStatus(box, view);
          writeFlightCache(key, view, view.ttlSeconds || 300);
        })
        .catch(function () {
          box.textContent = '';
          box.appendChild(el('div', 'entry-meta', t('page.flight.unavailable')));
        })
        .then(function () { button.disabled = false; });
    });
    return wrap;
  }
```

- [ ] **Step 6: Add the styles** to `page.css` (uses existing classes for text; only the button needs its own rule):

```css
.flight-refresh { background: none; border: 0; padding: 0 4px; font: inherit; color: inherit; cursor: pointer; }
.flight-refresh:disabled { opacity: .5; cursor: default; }
.flight-status:empty { display: none; }
.flight-updated { opacity: .7; font-size: .85em; }
```

- [ ] **Step 7: Run**

Run: `cd planner-api && ./gradlew test --tests 'PublishedFlightTest' --tests 'PersonalPageTest' --tests 'PageStringsTest' --tests 'MessageKeysTest' --tests 'StaticSiteRendererTest'`
Expected: PASS. `PageStringsTest` proves no English sentence slipped into `page.js`; if it flags the `' · '`/`' → '` separators as prose, they are punctuation, not words — adjust the test's allowance for symbols only, not for words.

- [ ] **Step 8: Verify in a browser** (per CLAUDE.md, a published page is cached five minutes: add `?cb=1`). Publish the sample trip, open `/p/<slug>?cb=1`, confirm the flight line shows, the ↻ button calls `/api/v1/public/trips/<slug>/flights` (Network tab), and a second click within the TTL makes no request. Assert on a rendered box (`getBoundingClientRect().height > 0`), not the `hidden` attribute.

- [ ] **Step 9: Checkpoint.**

---

### Task 13: The planner forms

**Files:**
- Create: `planner-web/js/pages/trip/flight-lookup.js`, `planner-web/scss/components/_flight.scss`
- Modify: `planner-web/js/api.js`, `js/i18n/en.js`, `js/pages/trip/checklist.js`, `js/pages/trip/itinerary.js`, `js/pages/trip.js` (merge the factory), `trip.html`, the stylesheet entry that imports components
- No frontend test suite exists: verification is `npm run check` plus a browser run.

**Interfaces:**
- Consumes: `GET /api/v1/trips/{id}/flights/lookup` (Task 9), the `flight` field on create/patch (Task 3).
- Produces (a factory `flightLookup()` merged into the page component): `flightCanLookup(form)`, `flightLookup(form)`, `flightChanged(form, alsoTouched)`, `flightPayload(form, isTransport)`, `flightFromRecord(item)`, `flightChip(flight)`, and form fields `flightNumber, flightSnapshot, flightStatus, flightNote, flightTouched, flightHad`.

- [ ] **Step 1: Add the API call** to `js/api.js`, in the itinerary group:

```js
  // Fills a form from a flight number and a start date. Never saves anything.
  lookupFlight: (id, number, date) =>
    get(`${trip(id)}/flights/lookup?number=${encodeURIComponent(number)}&date=${encodeURIComponent(date)}`),
```

- [ ] **Step 2: Add the words** to `js/i18n/en.js` (alphabetical with the rest, `flight.*` group):

```js
  'flight.label': "Flight number (optional)",
  'flight.lookup': "Look up flight",
  'flight.lookupNeedsDate': "Add a start date to look up a flight",
  'flight.busy': "Looking up…",
  'flight.filled': "Filled from {number}{operated} · {from} {departure} to {to} {arrival}",
  'flight.operatedAs': " (operated as {number})",
  'flight.saved': "{number}{operated} · {from} to {to}",
  'flight.notFound': "No live data found. Fill in the times yourself.",
  'flight.unavailable': "Flight lookup is unavailable right now. Fill in the times yourself.",
  'flight.chip': "{number} · {from} to {to}",
  'flight.chipNumberOnly': "{number}",
```

- [ ] **Step 3: Write the module** `js/pages/trip/flight-lookup.js`:

```js
import { t } from '../../i18n/index.js';

/**
 * The flight number field on both forms (the checklist Plan form and the
 * Itinerary tab's entry form). One module because the two forms name their
 * fields differently (planForm, entryForm), so the state lives on the form
 * object itself and every method takes the form it works on.
 *
 * Form fields it adds (see blankFlight):
 *   flightNumber    what the member typed
 *   flightSnapshot  the airports and numbers a lookup found, or the saved ones
 *   flightStatus    idle | busy | found | notFound | unavailable | saved
 *   flightNote      the line shown under the field
 *   flightTouched   the member typed or looked up: only then is a flight sent
 *   flightHad       the entry already had a flight, so emptying the field clears it
 *
 * The lookup never runs on save or on typing, so it cannot block a save.
 */

export const blankFlight = () => ({
  flightNumber: '',
  flightSnapshot: null,
  flightStatus: 'idle',
  flightNote: '',
  flightTouched: false,
  flightHad: false,
});

const operatedLabel = (operatingNumber) =>
  operatingNumber ? t('flight.operatedAs', { number: operatingNumber }) : '';

/** The form fields for an entry that already has a flight (or none). */
export function flightFromRecord(item) {
  const flight = item.flight;
  if (!flight) return blankFlight();
  return {
    flightNumber: flight.number || '',
    flightSnapshot: flight,
    flightStatus: 'saved',
    flightNote: flight.from && flight.to
      ? t('flight.saved', {
          number: flight.number,
          operated: operatedLabel(flight.operatingNumber),
          from: flight.from.iata,
          to: flight.to.iata,
        })
      : '',
    flightTouched: false,
    flightHad: true,
  };
}

export function flightLookup() {
  return {
    /** A number and a start date are both needed. */
    flightCanLookup(form) {
      return !!form.flightNumber.trim() && !!form.startDate && form.flightStatus !== 'busy';
    },

    /**
     * The member edited the number or the start date. Whatever a lookup found
     * belonged to the old values, so it is dropped rather than saved against a
     * different flight. Times already filled stay: they are ordinary field
     * values by now.
     */
    flightChanged(form, numberEdited) {
      form.flightSnapshot = null;
      form.flightNote = '';
      if (form.flightStatus !== 'busy') form.flightStatus = 'idle';
      if (numberEdited) form.flightTouched = true;
    },

    /** One click: look the flight up and fill the blank fields. Never overwrites. */
    async flightLookup(form) {
      if (!this.flightCanLookup(form)) return;
      form.flightStatus = 'busy';
      form.flightNote = '';
      try {
        const found = await this.api.lookupFlight(this.trip.id, form.flightNumber.trim(), form.startDate);
        if (found.status !== 'found') {
          form.flightStatus = found.status === 'notFound' ? 'notFound' : 'unavailable';
          form.flightSnapshot = null;
          return;
        }
        if (!form.startTime) form.startTime = found.departureTime || '';
        if (!form.endDate) form.endDate = found.arrivalDate || '';
        if (!form.endTime) form.endTime = found.arrivalTime || '';
        form.flightSnapshot = found.flight;
        form.flightTouched = true;
        form.flightStatus = 'found';
        form.flightNote = t('flight.filled', {
          number: found.flight.number,
          operated: operatedLabel(found.operatingNumber),
          from: found.flight.from?.iata || '',
          departure: found.departureTime || '',
          to: found.flight.to?.iata || '',
          arrival: found.arrivalTime || '',
        });
      } catch (error) {
        form.flightStatus = 'unavailable';
        form.flightSnapshot = null;
      }
    },

    /**
     * What to add to the save request. Nothing unless the member touched the
     * field, so opening and saving a form never rewrites a flight. An emptied
     * field on an entry that had one sends an empty number, which clears it.
     */
    flightPayload(form, isTransport) {
      if (!isTransport || !form.flightTouched) return {};
      const number = form.flightNumber.trim();
      if (!number) return form.flightHad ? { flight: { number: '' } } : {};
      return { flight: { ...(form.flightSnapshot || {}), number } };
    },

    /** The chip on a plan card or an itinerary row. */
    flightChip(flight) {
      if (!flight) return '';
      return flight.from && flight.to
        ? t('flight.chip', { number: flight.number, from: flight.from.iata, to: flight.to.iata })
        : t('flight.chipNumberOnly', { number: flight.number });
    },
  };
}
```

- [ ] **Step 4: Merge the factory.** In `js/pages/trip.js` find where the tab factories are merged (`grep -n "getOwnPropertyDescriptors" js/pages/trip.js`), import `flightLookup` from `./trip/flight-lookup.js` and add `flightLookup()` to the same list that merges `memberPicker`-style factories. Do not use spread (CLAUDE.md, "Object spread invokes getters").

- [ ] **Step 5: Wire the Plan form** in `checklist.js`. Import `blankFlight, flightFromRecord` from `./flight-lookup.js`. Then:

  1. In `blankPlan()` add `...blankFlight(),` beside the other fields.
  2. In `editPlan(plan)` add `...flightFromRecord(plan),` inside the object literal.
  3. In `savePlan()` add `...this.flightPayload(this.planForm, this.openItem?.category === 'TRANSPORTATION'),` to **both** the `updateItinerary` payload and the `addItinerary` payload (next to `countryCodes: this.planForm.countryCodes,`).

- [ ] **Step 6: Wire the entry form** in `itinerary.js` the same way: `...blankFlight(),` in `blankEntry()`, `...flightFromRecord(item),` in `openEditEntry(item)`, and `...this.flightPayload(this.entryForm, this.entryForm.category === 'TRANSPORTATION'),` into the `payload` object used by both `updateItinerary` and `addItinerary` (`grep -n "const payload" js/pages/trip/itinerary.js` to find it).

- [ ] **Step 7: Add the markup.** In `trip.html`, in the **Plan form**, immediately before the `<div class="field-row">` holding `plan-start-date`:

```html
                  <div class="field" x-show="openItem && openItem.category === 'TRANSPORTATION'" x-cloak>
                    <label for="plan-flight" data-i18n="flight.label"></label>
                    <div class="flight-input">
                      <input id="plan-flight" x-model="planForm.flightNumber" maxlength="12"
                             autocapitalize="characters" autocomplete="off"
                             @input="flightChanged(planForm, true)">
                      <button type="button" class="btn btn-secondary flight-lookup-btn"
                              :disabled="!flightCanLookup(planForm)"
                              :aria-label="$t('flight.lookup')" :title="planForm.startDate ? $t('flight.lookup') : $t('flight.lookupNeedsDate')"
                              @click="flightLookup(planForm)">
                        <span x-show="planForm.flightStatus !== 'busy'" aria-hidden="true">🔍</span>
                        <span x-show="planForm.flightStatus === 'busy'" x-cloak data-i18n="flight.busy"></span>
                      </button>
                    </div>
                    <p class="field-hint" role="status" x-show="planForm.flightNote" x-text="planForm.flightNote" x-cloak></p>
                    <p class="field-hint" role="status" x-show="planForm.flightStatus === 'notFound'" x-cloak data-i18n="flight.notFound"></p>
                    <p class="field-hint" role="status" x-show="planForm.flightStatus === 'unavailable'" x-cloak data-i18n="flight.unavailable"></p>
                  </div>
```

and make the Plan form's start-date input clear a stale result by adding `@change="flightChanged(planForm, false)"` to `<input id="plan-start-date" …>`.

In the **entry form**, the same block immediately before `<div class="field-row">` holding `entry-start-date`, with `entryForm` in place of `planForm`, ids `entry-flight`, and the show condition `entryForm.category === 'TRANSPORTATION'`. Add `@change="flightChanged(entryForm, false)"` to `<input id="entry-start-date" …>` and to the category `<select id="entry-category">` no handler is needed: the block hides itself, and `flightPayload` sends nothing for a non-transport form.

- [ ] **Step 8: Add the chips.** Find the plan card and the itinerary row templates:

Run: `cd planner-web && grep -n "openItemPlans\|filteredItinerary" trip.html | head`

Directly under the element that renders the plan/entry `description` in each, add:

```html
<span class="flight-chip" x-show="plan.flight" x-text="flightChip(plan.flight)" x-cloak></span>
```

(use the loop variable that template already uses: `plan` in the checklist drawer's plans, `item` in the itinerary rows).

- [ ] **Step 9: Add the styles** `scss/components/_flight.scss` and import it beside the other components (`grep -rn "panel-mode" scss/*.scss` shows the import list):

```scss
.flight-input {
  display: flex;
  gap: 8px;
  align-items: stretch;

  input { flex: 1 1 auto; min-width: 0; }
}

.flight-lookup-btn { flex: 0 0 auto; }

.flight-chip {
  display: inline-block;
  font-size: 0.85em;
  opacity: 0.8;
  margin-left: 6px;
}
```

Run `cd planner-web && npm run css`.

- [ ] **Step 10: Run the checks**

Run: `cd planner-web && npm run check`
Expected: PASS (every `t()` key used exists and every key present is used; no prose in code).

- [ ] **Step 11: Verify in a browser** (Playwright or a real browser; API on :8080, frontend on :3000, `AERODATABOX_KEY`/`AVIATIONSTACK_KEY` unset is fine for the first pass):
  1. Open a trip, add a checklist item in the transport category, click **Start to add**: the Flight number field is visible. Do the same for a non-transport item: it is not.
  2. In the Itinerary tab, **Add entry**: choose category Transport and the field appears; choose Food and it disappears.
  3. With no keys set, type `KL2842`, fill a start date, click the icon: the line reads "Flight lookup is unavailable right now", the form stays usable, and Save works.
  4. Save with the number only; reopen Edit: the number is prefilled and the plan card shows the chip `KL2842`.
  5. Empty the field, save: the chip disappears (the number was cleared).
  6. With the owner's approval, set the keys and repeat step 3 for KL2842 on a near date: times fill, the note shows "operated as BT857".
  7. Phone width: load the page in a 400px `<iframe>` on the same origin (CLAUDE.md, Traps) and confirm the number and icon stay on one row.
  8. Remember to clear `plannerApiBase` from localStorage if a non-8080 port was used.

- [ ] **Step 12: Checkpoint.**

---

### Task 14: Documentation, spec sync and the final gate

**Files:**
- Create: `docs/external-apis/aerodatabox.md`, `docs/external-apis/aviationstack.md`
- Modify: `docs/external-apis/README.md`, `CLAUDE.md`, `.claude/specs/2026-09-26-flight-lookup-design.md`, `deploy` script (find with `ls docs/deploy ../deploy.sh planner-api/deploy.sh 2>/dev/null`; add the env lines wherever `RATES_CRON`/mail event variables are set), the `free-tier-usage` skill (`.claude/skills/free-tier-usage/SKILL.md`)

- [ ] **Step 1: Write `docs/external-apis/aerodatabox.md`.**

```markdown
# AeroDataBox

The source of truth for a flight: schedule, airports, times with real offsets,
terminal, status and aircraft.

- **Base URL:** `https://aerodatabox.p.rapidapi.com` (`app.flights.aerodatabox.base-url`).
- **Auth:** RapidAPI headers `X-RapidAPI-Key` (the key, `AERODATABOX_KEY`) and
  `X-RapidAPI-Host` (the base URL's own host). No key means the service is off, not
  that the app fails.
- **Client:** `AeroDataBoxClient`. Timeout 8 s.

## Calls
| Call | Trigger | Frequency |
|---|---|---|
| `GET /flights/number/{number}/{date}?dateLocalRole=Departure` | The form's lookup icon (up to two per click: the booked number, then its operating number); a reader's refresh on a published flight when the cached record is missing or past its TTL; the nightly prewarm | On click or nightly, deduped by flight and date |

## Quota
Free tier **400 calls a month**. The app stops at **90% (360)** so the last calls
stay free. Both numbers are configurable: `AERODATABOX_MONTHLY_LIMIT`,
`AERODATABOX_CAP_PERCENT`. Counted in `api_usage` by UTC calendar month; a new month
is a new row, so no reset job exists. A call counts when attempted, even if it fails.

## Caching
`flight_records` (install-wide, keyed by operating number and local departure date).
TTL by time to departure: more than 3 days 72 h, within 3 days 24 h, within 24 h 1 h
(`FLIGHT_TTL_FAR_AHEAD`, `FLIGHT_TTL_WITHIN_THREE_DAYS`, `FLIGHT_TTL_WITHIN_ONE_DAY`).
Frozen once landed. A genuine empty answer is stored as a miss for
`FLIGHT_NEGATIVE_TTL` (6 h). A failed refresh serves the last record marked stale.

## Failure behaviour
No key, cap reached, timeout, 4xx, 5xx: the lookup answers `unavailable` and the
public refresh returns the cached record or nothing. Never stored as a miss.

## Quirks
- **"No such flight" is an empty body**, not a 404 or an error.
- A codeshare number (KL2842) is not found; the operating flight (BT857) is. Codeshare
  resolution is AviationStack's job.
- Times are `2026-10-24 07:30+03:00` (a space, not a `T`); the app converts.
- A read timeout means throttling, not a bug in the request.
- The airport board endpoint reports `codeshareStatus: Unknown` for most Tallinn flights.
```

- [ ] **Step 2: Write `docs/external-apis/aviationstack.md`.**

```markdown
# AviationStack

Used for two things only: finding a codeshare's operating flight, and live extras
(gate, baggage belt, delay, actual times) close to departure. Never for scheduled
times or airports.

- **Base URL:** `http://api.aviationstack.com/v1` (`app.flights.aviationstack.base-url`).
- **Auth:** query parameter `access_key` (`AVIATIONSTACK_KEY`). **The free plan is HTTP
  only, so the key travels in plain text.** Rotate it and switch the base URL to HTTPS
  if the plan is upgraded. The key is never logged.
- **Client:** `AviationStackClient`. Timeout 8 s.

## Calls
| Call | Trigger | Frequency |
|---|---|---|
| `GET /flights?flight_iata={number}` (codeshare) | The form's lookup, only after AeroDataBox found nothing and no mapping is known | Once per flight number ever (the pairing is stored) |
| `GET /flights?flight_iata={number}` (live) | A reader's refresh, only from 2 h before departure until landed, when the last live fetch is over 15 min old | At most every 15 min per flight |

## Quota
Free plan **100 calls a month**; the app stops at **90%**. `AVIATIONSTACK_MONTHLY_LIMIT`,
`AVIATIONSTACK_CAP_PERCENT`. Counted in `api_usage`.

## Caching
Codeshare pairings: `codeshare_mappings`, permanent. Live extras: on the flight's
`flight_records` row with their own fetch time, TTL `FLIGHT_LIVE_TTL` (15 m), window
`FLIGHT_LIVE_WINDOW` (2 h).

## Failure behaviour
Unavailable is never a miss. Without the extras the page still shows the AeroDataBox
data.

## Quirks
- **It labels airport local time as UTC:** `07:30+00:00` for a 07:30 Tallinn
  departure. The offset is dropped, and its scheduled times are never used.
- The free plan returns only flights around today (yesterday and today for a daily
  flight). A `flight_date` filter answers **403 `function_access_restricted`**.
- Errors arrive as an `error` object with a 200 or a 403, so the status code alone
  is not enough.
- A codeshare appears as its own row whose `flight.codeshared` names the operating
  flight (`bt857`, lower case).
```

- [ ] **Step 3: Add table rows** to `docs/external-apis/README.md` for both services, in the same column format as the existing rows (service, purpose, file, config key, cadence).

- [ ] **Step 4: Add the deploy variables.** Wherever the deploy script sets `RATES_CRON` and the mail-event variables (`grep -rn "RATES_CRON" --include="*.sh" --include="*.md" .`), add `AERODATABOX_KEY` and `AVIATIONSTACK_KEY` as **Secret Manager secrets** (like `JWT_SECRET`) and the optional tunables as plain env vars, plus `FLIGHTS_PREWARM_CRON`. A missing key must not fail the deploy.

- [ ] **Step 5: Update the `free-tier-usage` skill** (`.claude/skills/free-tier-usage/SKILL.md`): add AeroDataBox (400 a month, cap 360) and AviationStack (100 a month, cap 90), and note that the live counts are in the `api_usage` table (or `data/api-usage.yml` locally): `SELECT service, month, calls FROM api_usage ORDER BY month DESC;`.

- [ ] **Step 6: Add a "Flight lookup" section to `CLAUDE.md`** (after the Exchange rates section), covering what is hard to see from one file: the two services' roles; that codeshare resolution is best-effort and lives only in the entry form's lookup; the `flight` snapshot versus the cache and why gate/baggage are not on the entry; the three lookup statuses and that only a genuine empty answer is cached as a miss; the month-keyed counter and why there is no reset job; refresh-on-read as the real mechanism with the nightly job as a warm-up; the public endpoint being addressed by slug, number and date and never an open proxy; AviationStack's mislabelled UTC and HTTP-only key; and that coordinates are never shipped on a published page. Keep it to the same density as the weather section.

- [ ] **Step 7: Sync the spec.** Edit `.claude/specs/2026-09-26-flight-lookup-design.md`: in **API**, replace "addressed by trip slug and entry id" with "addressed by trip slug, flight number and local date (`GET /api/v1/public/trips/{slug}/flights?number=&date=`); the entry is found by matching the number, booked or operating, and the local start date", and add `TripRepository.findAllPublished()` to the nightly job section.

- [ ] **Step 8: Full verification**

Run: `cd planner-api && ./gradlew test`
Expected: BUILD SUCCESSFUL. Then read the skipped count:

Run: `cd planner-api && grep -ho 'skipped="[0-9]*"' build/test-results/test/*.xml | sort | uniq -c`
Expected: only `skipped="0"` (Docker/Colima up). A non-zero count means container tests did not run: fix Docker before trusting the result.

Run: `cd planner-web && npm run check`
Expected: PASS.

Run: `cd planner-api && ./gradlew test --tests 'OpenApiDocTest' --tests 'MessageKeysTest' --tests 'MigrationsAreSchemaOnlyTest' --tests 'YamlModeStartupTest' --tests 'DatabaseModeApplicationTest'`
Expected: PASS (`DatabaseModeApplicationTest` boots the whole app on Postgres with the new tables).

- [ ] **Step 9: Live check, with the owner's approval per call.** Two calls only, and only after asking: (a) the form lookup for KL2842 on a date within a few days, expecting `found` with `operatingNumber: BT857`; (b) a public refresh for the same flight inside its window. Report the responses; do not loop on failure.

- [ ] **Step 10: Final checkpoint** — tell the owner the work is ready to review and commit (do not commit).

---

## Self-Review (run against the spec)

**Spec coverage**
- Entry form: transport-only field, one-click fill, blank-only fill, no run on save, wall-clock times, edit clears snapshot, number-only valid → Tasks 3, 9, 13.
- Lookup order incl. mapping, miss caching only when genuine, cache warming by lookup → Tasks 8, 9.
- Published page: nothing on load, click refresh, browser cache, "Updated" per group, quiet failure → Tasks 10, 12.
- Data: entry `flight`, install-wide cache, codeshare map, `api_usage`, YAML shapes, V13 → Tasks 1, 2, 5.
- TTLs, landed rule, live window, stale on failure → Task 8, 10.
- Quotas 400/100 at 90%, `tryAcquire`, no reset job → Tasks 1, 4, 6, 7.
- Nightly job: published only, 72 h, dedupe, soonest first, stop at cap, AeroDataBox only, docs → Task 11.
- API: member lookup, public endpoint, OpenAPI, message keys → Tasks 3, 9, 10.
- Published-page changes, no coordinates, personal-page narrowing, `page.*` keys → Task 12.
- Frontend: shared module, where things show, payload rules, chips, i18n → Task 13.
- Configuration, failure behaviour, testing, documentation → Tasks 4, 11, 14.
- Decisions taken in review (flights on by default, `/api/*` proxy) → no setting added; Task 10 route.

**Placeholder scan:** the test bodies in Tasks 8, 9, 10 and 11 list case names with fixtures rather than every assertion; each case name states the assertion and the data comes from the real payloads in Tasks 6 and 7, but an implementer must write the bodies, so they are the plan's thinnest spots. No `TBD`/`TODO` remain.

**Type consistency:** `Fetch.Status` (FOUND, NOT_FOUND, UNAVAILABLE) and `Fetch.of/found/notFound/unavailable` (Task 4, 7); `FlightData.Result(status, record, stale)` (Task 8) is what `FlightLookup` (Task 9), `FlightStatusService` (Task 10) and the job (Task 11) read; `FlightFreshness.departureOf` static and `liveTtl()` (Task 10 adds it) are used by `FlightStatusService`; `FlightSnapshot` fields (`number, operatingNumber, from, to, terminalFrom, terminalTo`) match the entry, the request, the lookup response, `flightPayload` and `PublishedTrip.Flight` mapping; service constants `AeroDataBoxClient.SERVICE` / `AviationStackClient.SERVICE` are the counter keys the job and clients share.

**Review Focus coverage:** 1 → Tasks 4 and 9; 2 → Task 9 controller test; 3 → Task 7; 4 → Task 1; 5 → Tasks 10 and 12; 6 → Tasks 6, 8 and 9.
