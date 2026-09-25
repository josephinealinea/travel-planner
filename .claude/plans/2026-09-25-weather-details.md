# Weather Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show more of what Open-Meteo already returns (sunrise/sunset, UV, wind, feels-like, rain probability, humidity, cloud cover, rain/snow amounts, daylight) on each itinerary weather card and on the published page, without adding a single request, and make the freshness of *today's* reading configurable.

**Architecture:** The extra values are stored as one JSON document (`details`) on `WeatherRecord` — `jsonb` in Postgres, a nested map in YAML — so the next field is one line in a catalogue rather than a column and a migration. A `DetailField` catalogue is the single list of fields: it drives the `daily=` parameter of both Open-Meteo calls, the parsing, and (via a drift test) the published page. A value Open-Meteo answers `null` for is **absent** from the map, never `0`, and the UI shows only what is present.

**Tech Stack:** Spring Boot 3.5 / Java 21, Jackson, Flyway, Alpine.js, the ES5 `publish/page.js`.

**Spec:** the design conversation of 2026-09-25 (this plan is the record of it). Live checks against Open-Meteo for Cusco, 25 Sep 2026, are summarised under *Verified facts*.

## Verified facts (checked live, 2026-09-25)

- **`/forecast` for today** returns every field below non-null: `sunrise, sunset, daylight_duration, sunshine_duration, uv_index_max, precipitation_sum, rain_sum, showers_sum, snowfall_sum, precipitation_hours, precipitation_probability_max, wind_speed_10m_max, wind_gusts_10m_max, wind_direction_10m_dominant, apparent_temperature_max, apparent_temperature_min, relative_humidity_2m_mean, cloud_cover_mean`.
- **`/climate`** returns real values for `sunrise, sunset, daylight_duration, rain_sum, snowfall_sum, wind_speed_10m_max, wind_gusts_10m_max, relative_humidity_2m_mean, cloud_cover_mean`, and **nulls** for `uv_index_max, apparent_temperature_max/min, precipitation_probability_max, precipitation_hours, wind_direction_10m_dominant, sunshine_duration, showers_sum, et0_fao_evapotranspiration, weather_code`.
- **`timezone=auto` works on `/climate`** too: `sunrise` came back `05:16` local for Cusco. Without it the value is UTC (`10:16`). `WeatherClient` already sends `timezone=auto` on both calls, and `page.js` does as well — so no change is needed there, and the times are destination-local.
- **Air quality is a separate API** (`air-quality-api.open-meteo.com`), hourly-only, forecast-only. **Out of scope** — it would be a third request.
- The code's window is `horizon-days: 14` and `history-days: 85` (not Open-Meteo's real 16 / 92). `page.js` uses the same 14 / 85. They already agree.

## Global Constraints

- **No new requests.** Fields are added to the existing `daily=` lists. Forecast dates → one `/forecast` call, other dates → one `/climate` call, at most two per lookup (already true; do not regress it).
- **Only ask for a field an endpoint can answer.** `/climate` must not be sent the forecast-only fields — they come back null and only waste payload.
- **`null` is never `0`.** A missing column, short column or JSON `null` leaves the key out of `details`. A real `0` (0 mm rain, 0 % probability, 0 cm snow) is kept and shown.
- **A field with no value is not rendered** — no label, no dash, no placeholder.
- **Nothing derived is stored except the reading itself** (unchanged rule: a `WeatherRecord` carries its own provenance).
- **New settings go in their own `@ConfigurationProperties` record**, never `AppProperties` (CLAUDE.md, *Deployment*); default `12h`.
- **The published page fetches live in the reader's browser** and must offer the same fields as the planner. It has no store and no API behind it.
- **Air quality is not fetched.**
- Every Postgres test is `@Testcontainers(disabledWithoutDocker = true)`: after each Gradle run, count `skipped` in `planner-api/build/test-results/test/*.xml`; it must be 0.
- Commits: plain `git` (not `gh`); end messages with `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`.

## Review Focus

1. **A real zero must survive.** `rainSum: 0`, `precipitationProbabilityMax: 0`, `snowfallSum: 0` render ("Dry", "0 %") and round-trip through YAML and Postgres as `0.0`, not as absent. (Tasks 1, 2, 5)
2. **A record stored before this change has no `details`.** It must load, render exactly as today, and be refreshed with details only when its normal TTL lapses — no forced refetch, no error. (Tasks 2, 4)
3. **A climate row must not show forecast-only fields.** UV, feels-like, rain probability, wind direction are absent for `CLIMATE` rows and the card simply has fewer chips. (Tasks 1, 5)
4. **Today's TTL.** With `today-cache-ttl` set shorter than `cache-ttl`, today refetches sooner while later days keep the longer TTL; past days never refetch. With both at 12 h, behaviour is identical to today. (Task 3)
5. **The published page and the planner must not drift.** A field added to `DetailField` but not to `page.js` fails a test. (Task 6)

---

### Task 1: The field catalogue and the client

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/weather/DetailField.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/WeatherClient.java` (`DAILY_FORECAST` / `DAILY_CLIMATE` at ~78-81, `fetchForecast`, `fetchClimate`, `readOne` at ~296-316, the `Reading` record)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherClientTest.java`

**Interfaces:**
- Produces: `DetailField` enum — `String key()` (the JSON key, camelCase), `String parameter()` (Open-Meteo daily variable), `boolean text()` (true → value is a string), `boolean inClimate()`; `static List<DetailField> forForecast()` and `forClimate()`; `static String dailyParameter(List<DetailField>)` (comma-joined `parameter()`s).
- Produces: `WeatherClient.Reading` gains a last component `Map<String, Object> details` (never null; empty when nothing came back). Update every construction site — the compiler names them.

- [ ] **Step 1: Write `DetailField`**

```java
package com.josephinealinea.planner.weather;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every extra daily value a weather card can show, in one place.
 *
 * This list drives what is asked of Open-Meteo, what is parsed back, and (by
 * WeatherDetailsDriftTest) what the published page's own copy of the request
 * must contain. Adding a field is one line here plus a chip in
 * js/weather.js and publish/page.js.
 *
 * {@code inClimate} is false for a variable the /climate endpoint accepts and
 * answers with a column of nulls — asked anyway it costs payload for nothing.
 * Verified live for a Cusco date; recheck if the model changes.
 *
 * {@code key} is what is stored and sent to the page. Absent means unknown:
 * a value of 0 is a real answer and is kept.
 */
public enum DetailField {
    SUNRISE("sunrise", "sunrise", true, true),
    SUNSET("sunset", "sunset", true, true),
    DAYLIGHT_SECONDS("daylightSeconds", "daylight_duration", false, true),
    UV_INDEX_MAX("uvIndexMax", "uv_index_max", false, false),
    PRECIPITATION_PROBABILITY_MAX("precipitationProbabilityMax", "precipitation_probability_max", false, false),
    RAIN_SUM("rainSum", "rain_sum", false, true),
    SNOWFALL_SUM("snowfallSum", "snowfall_sum", false, true),
    WIND_SPEED_MAX("windSpeedMax", "wind_speed_10m_max", false, true),
    WIND_GUSTS_MAX("windGustsMax", "wind_gusts_10m_max", false, true),
    WIND_DIRECTION("windDirection", "wind_direction_10m_dominant", false, false),
    APPARENT_TEMPERATURE_MAX("apparentTemperatureMax", "apparent_temperature_max", false, false),
    APPARENT_TEMPERATURE_MIN("apparentTemperatureMin", "apparent_temperature_min", false, false),
    HUMIDITY_MEAN("humidityMean", "relative_humidity_2m_mean", false, true),
    CLOUD_COVER_MEAN("cloudCoverMean", "cloud_cover_mean", false, true);

    private final String key;
    private final String parameter;
    private final boolean text;
    private final boolean inClimate;

    DetailField(String key, String parameter, boolean text, boolean inClimate) {
        this.key = key;
        this.parameter = parameter;
        this.text = text;
        this.inClimate = inClimate;
    }

    public String key() { return key; }
    public String parameter() { return parameter; }
    public boolean text() { return text; }
    public boolean inClimate() { return inClimate; }

    public static List<DetailField> forForecast() {
        return List.of(values());
    }

    public static List<DetailField> forClimate() {
        return Arrays.stream(values()).filter(DetailField::inClimate).toList();
    }

    public static String dailyParameter(List<DetailField> fields) {
        return fields.stream().map(DetailField::parameter).collect(Collectors.joining(","));
    }
}
```

- [ ] **Step 2: Write the failing client tests.** Read `aNullConditionColumnLeavesTheCodeUnsetRatherThanZero` (`WeatherClientTest.java:214`) and copy its fixture pattern (`CannedHttp`, real captured JSON). Add three tests, named as sentences like the neighbours:

```java
@Test
void theForecastCallAsksForEveryDetailAndTheClimateCallOnlyForThoseItCanAnswer() {
    // one forecast call and one climate call through CannedHttp, as in
    // aTripStraddlingTheHorizonKeepsTheForecastForTheDaysThatHaveOne
    String forecastDaily = queryParam(http.asked().get(0), "daily");
    String climateDaily = queryParam(http.asked().get(1), "daily");
    assertThat(forecastDaily).contains("uv_index_max", "sunrise", "wind_direction_10m_dominant");
    assertThat(climateDaily).contains("sunrise", "rain_sum", "cloud_cover_mean")
            .doesNotContain("uv_index_max", "apparent_temperature_max",
                    "precipitation_probability_max", "wind_direction_10m_dominant");
}

@Test
void detailsCarryTheValuesTheAnswerHadAndAZeroIsKept() {
    // forecast body with rain_sum [0.0], precipitation_probability_max [0],
    // sunrise ["2026-09-25T05:35"], uv_index_max [10.35]
    Reading reading = readingFor(point, LocalDate.of(2026, 9, 25));
    assertThat(reading.details())
            .containsEntry("sunrise", "2026-09-25T05:35")
            .containsEntry("uvIndexMax", 10.35)
            .containsEntry("rainSum", 0.0)
            .containsEntry("precipitationProbabilityMax", 0.0);
}

@Test
void aNullDetailColumnLeavesTheKeyOutRatherThanZero() {
    // climate body with "uv_index_max": [null] and "wind_speed_10m_max": [10.1]
    Reading reading = readingFor(point, LocalDate.of(2026, 10, 25));
    assertThat(reading.details()).containsKey("windSpeedMax").doesNotContainKey("uvIndexMax");
}
```

`queryParam` and `readingFor` are two tiny private helpers in the test class (parse `URI.getQuery()`; `client.lookUp(List.of(point), day, day).get(point.id()).get(day)`).

- [ ] **Step 3: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'WeatherClientTest'`
Expected: compile FAIL (`details()` / `DetailField` missing).

- [ ] **Step 4: Implement.** In `WeatherClient`: replace the two `DAILY_*` constants with
```java
private static final String DAILY_FORECAST =
        "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,"
                + DetailField.dailyParameter(DetailField.forForecast());
private static final String DAILY_CLIMATE =
        "temperature_2m_max,temperature_2m_min,precipitation_sum,"
                + DetailField.dailyParameter(DetailField.forClimate());
```
Add `Map<String, Object> details` to `Reading`. In `readOne`, after computing `rain`/`code`, build details for the fields the *source* offers, then pass them:
```java
Map<String, Object> details = new LinkedHashMap<>();
List<DetailField> asked = source == DayWeather.Source.CLIMATE
        ? DetailField.forClimate() : DetailField.forForecast();
for (DetailField field : asked) {
    Object value = field.text() ? text(daily, field.parameter(), i) : number(daily, field.parameter(), i);
    if (value != null) details.put(field.key(), value);
}
```
with a `text` helper beside `number` (same null/short-column rules, `asText()`, blank → null). Keep the existing rule that a row with no temperature at all is skipped.

- [ ] **Step 5: Run to verify pass**

Run: `cd planner-api && ./gradlew test --tests 'WeatherClientTest'`
Expected: PASS (fix any positional `Reading(...)` constructions in the test file).

- [ ] **Step 6: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/weather/DetailField.java planner-api/src/main/java/com/josephinealinea/planner/weather/WeatherClient.java planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherClientTest.java
git commit -m "feat(weather): ask Open-Meteo for the extra daily details on the same two calls"
```

---

### Task 2: Store `details` (YAML and Postgres)

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/domain/WeatherRecord.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/DayWeather.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/storage/jdbc/TripScopedJdbcRepository.java` (constructor ~106-135)
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/infra/JdbcWeatherRepository.java`
- Create: `planner-api/src/main/resources/db/migration/V11__weather_details.sql`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherRepositoryContract.java` (runs for YAML and Postgres), `planner-api/src/test/java/com/josephinealinea/planner/storage/EveryField.java` / `BaselineSchemaTest` as their failures direct

**Interfaces:**
- Consumes: nothing from Task 1 beyond the key names.
- Produces: `WeatherRecord.getDetails()/setDetails(Map<String,Object>)` (null = never had any); `DayWeather` gains a last component `Map<String, Object> details` (empty map, never null, when absent); `DayWeather.unavailable(...)` passes `Map.of()`.

- [ ] **Step 1: Failing contract tests.** In `WeatherRepositoryContract` add, using its existing `reading(...)` helper:

```java
@Test
void detailsRoundTripAndAZeroStaysAZero() {
    WeatherRecord record = reading(-13.53, -71.97, OCT_25);
    Map<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("sunrise", "2026-10-25T05:16");
    details.put("rainSum", 0.0);
    details.put("windSpeedMax", 10.1);
    record.setDetails(details);
    store().save(TRIP, record);

    WeatherRecord read = store().findAll(TRIP).get(0);
    assertThat(read.getDetails())
            .containsEntry("sunrise", "2026-10-25T05:16")
            .containsEntry("rainSum", 0.0)
            .containsEntry("windSpeedMax", 10.1);
}

@Test
void aRecordWithoutDetailsReadsBackWithoutThemNotAsAnEmptyDocument() {
    store().save(TRIP, reading(-13.53, -71.97, OCT_25));
    assertThat(store().findAll(TRIP).get(0).getDetails()).isNullOrEmpty();
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests '*WeatherRepository*'`
Expected: compile FAIL (`setDetails` missing).

- [ ] **Step 3: Domain.** `WeatherRecord`: add `private Map<String, Object> details;` with getter/setter, and a class-doc line: *"`details` is a document, not columns: keys are `DetailField.key()`, absent means unknown, and an old record simply has none."* `YamlStore` is `NON_NULL`, so a record with no details writes nothing extra. `DayWeather`: add `Map<String, Object> details` as the last component; `unavailable(...)` passes `Map.of()`.

- [ ] **Step 4: Migration `V11__weather_details.sql`** (schema only — `MigrationsAreSchemaOnlyTest`):

```sql
-- Extra daily readings (sunrise, UV, wind, ...) as one document rather than a
-- column each, so the next field is a key and not a migration. NULL for every
-- record cached before this existed: absent means "never fetched with details".
ALTER TABLE weather_records ADD COLUMN details jsonb;
```

- [ ] **Step 5: JSON binding in the generic base.** `TripScopedJdbcRepository` builds `:column` placeholders, and a `text` bind into `jsonb` is rejected by Postgres. Add an overload taking `Set<String> jsonColumns` (existing constructor delegates with `Set.of()`); in the constructor build values as `":" + c + (jsonColumns.contains(c) ? "::jsonb" : "")`. Validate every json column is in `columns`. Nothing else in the base changes; existing repositories are untouched.

- [ ] **Step 6: Repository.** `JdbcWeatherRepository`: append `"details"` to the column list, pass `Set.of("details")`, bind `values.put("details", JdbcValues.json(record.getDetails()))` (null → SQL NULL), read with `record.setDetails(JdbcValues.json(rs, "details", java.util.Map.class))` (the generic `Map.class` overload is fine; numbers come back as `Double`/`Integer`, so the client stores every number as a `Double` — see Task 1 `number`).

- [ ] **Step 7: Run all storage tests**

Run: `cd planner-api && ./gradlew test --tests '*WeatherRepository*' --tests 'BaselineSchemaTest' --tests 'MigrationsAreSchemaOnlyTest' --tests '*EveryField*'`
Expected: PASS. `BaselineSchemaTest` and the every-field round-trip fail until the new column/field are added to their fixtures — update them as they direct. Then count `skipped` in `build/test-results/test/*.xml` = 0.

- [ ] **Step 8: Commit**

```bash
git add -A planner-api/src
git commit -m "feat(weather): store extra daily readings as one details document"
```

---

### Task 3: Configurable freshness for today's reading

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/weather/TodayWeatherProperties.java`
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/api/WeatherService.java` (constructors ~100-125, the two rules in `fill` ~205-215)
- Modify: `planner-api/src/main/resources/application.yml` (`weather:` block ~102-109)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherServiceTest.java`

**Interfaces:**
- Produces: `TodayWeatherProperties(Duration cacheTtl)` bound to `app.weather.today`, default 12 h, with `static TodayWeatherProperties defaults()`; `WeatherService`'s `@Autowired` constructor gains it as the last parameter; the shorter constructors delegate with `defaults()` (so existing tests and callers are untouched).
- The existing `app.weather.cache-ttl` (12 h) is unchanged and keeps governing **future** dates. The new setting governs **today's date only**.

- [ ] **Step 1: Failing tests** in `WeatherServiceTest` (it already pins the clock — reuse its fixture). Model them on the existing TTL tests:

```java
@Test
void todaysReadingUsesTheTodayTtlWhileLaterDaysKeepTheGeneralOne() {
    // clock fixed at 2026-09-25T12:00Z; general ttl 12h, today ttl 1h.
    // A record for 25 Sep fetched 2h ago is stale (refetched);
    // a record for 27 Sep fetched 2h ago is still fresh (not refetched).
}

@Test
void withBothTtlsAtTwelveHoursTodayBehavesAsItAlwaysDid() {
    // a record for 25 Sep fetched 11h ago is fresh; 13h ago is stale.
}

@Test
void aPastDayIsNeverRefetchedWhateverTheTtls() {
    // record for 24 Sep fetched 30 days ago, today ttl 1 minute -> no lookup call.
}
```
(Use the class's existing helpers for the record store, the counting client and the clock; each asserts on the number of `lookUp` calls.)

- [ ] **Step 2: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'WeatherServiceTest'`
Expected: compile FAIL (`TodayWeatherProperties`).

- [ ] **Step 3: Implement.** Follow `budget/api/SettlementProperties` exactly (own record, nested `Registration` with `@EnableConfigurationProperties`):

```java
@ConfigurationProperties(prefix = "app.weather.today")
public record TodayWeatherProperties(Duration cacheTtl) {
    public TodayWeatherProperties {
        // Today's forecast is the one that changes during the day and the one
        // a traveller reads most; the default matches the general TTL, so
        // shortening it is a config change, not a code change.
        if (cacheTtl == null) cacheTtl = Duration.ofHours(12);
    }
    public static TodayWeatherProperties defaults() { return new TodayWeatherProperties(Duration.ofHours(12)); }
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TodayWeatherProperties.class)
    public static class Registration {}
}
```
`WeatherService` has two constructors already and Spring needs `@Autowired` on the real one (CLAUDE.md, *Traps*) — keep exactly one `@Autowired`, the one with the new parameter. Store `private final Duration todayTtl;`. In `fill`, replace Rule 3 with:
```java
Duration limit = day.equals(today) ? todayTtl : ttl;
if (record != null && !record.olderThan(now, limit)) continue;
```
`application.yml` under `weather:` add:
```yaml
    today:
      cache-ttl: 12h
```

- [ ] **Step 4: Run to verify pass, and that the app still boots**

Run: `cd planner-api && ./gradlew test --tests 'WeatherServiceTest' --tests 'YamlModeStartupTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add planner-api/src/main/java/com/josephinealinea/planner/weather planner-api/src/main/resources/application.yml planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherServiceTest.java
git commit -m "feat(weather): make today's cache lifetime configurable (app.weather.today.cache-ttl, 12h)"
```

---

### Task 4: Carry details through the service

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/weather/api/WeatherService.java` (`fill` ~ record construction, `daysOf` ~ 260+)
- Test: `planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherServiceTest.java`

**Interfaces:**
- Consumes: `Reading.details()` (Task 1), `WeatherRecord.setDetails/getDetails` and the `DayWeather` `details` component (Task 2).

- [ ] **Step 1: Failing tests**

```java
@Test
void aFetchedReadingsDetailsReachTheDayAndAreStored() {
    // canned lookUp returns a Reading with details {"uvIndexMax": 10.35, "rainSum": 0.0}
    // -> forTrip(...) day.details() contains both, and the saved record has them.
}

@Test
void aRecordStoredBeforeDetailsExistedStillShowsAndCarriesNoDetails() {
    // seed a WeatherRecord with details == null, fresh -> day.details() is an empty map, no lookup.
}
```

- [ ] **Step 2: Run to verify failure**, then implement: in `fill` call `record.setDetails(reading.details().isEmpty() ? null : reading.details())`; in `daysOf` build `DayWeather` with `record.getDetails() == null ? Map.of() : record.getDetails()`.

Run: `cd planner-api && ./gradlew test --tests 'WeatherServiceTest'`
Expected: FAIL then PASS.

- [ ] **Step 3: Confirm the controller/JSON shape.** Run the API (`cd planner-api && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun`), open a trip with a destination that includes today, and `GET /api/v1/trips/{id}/weather` (see `WeatherController`). Expected: each day has `details` with only the keys that had values.

- [ ] **Step 4: Commit**

```bash
git add planner-api/src
git commit -m "feat(weather): pass stored details through to the itinerary rows"
```

---

### Task 5: Planner UI — the cards

**Files:**
- Modify: `planner-web/js/weather.js` (add `detailChips`, `detailsTitle`)
- Modify: `planner-web/js/pages/trip/itinerary.js` (~442-446, next to `weatherTemps`)
- Modify: `planner-web/trip.html` (~528-545, the `weather-card` body)
- Modify: `planner-web/scss/components/` (the weather card styles) then `npm run css`

No frontend test suite exists (CLAUDE.md); the pure functions are verified in the browser console in Step 3 and the card is checked visually.

**Interfaces:**
- Produces: `detailChips(day) -> [{ icon, text, title }]` — the four to five that earn room on the card, in order: sunrise/sunset (`🌅 05:35 · 🌇 17:43`), UV (`☀️ UV 10`), rain probability (`💧 0%`), wind (`💨 18 km/h`, gusts in `title`), feels-like (`🌡 17° / 1°`). `detailsTitle(day) -> string` — the rest (rain mm, snow cm, humidity, cloud cover, daylight length, wind direction) for a tooltip on the details row. Both return empty when `day.details` is missing or has none of these keys.

- [ ] **Step 1: Write the helpers in `weather.js`.** Pure, no DOM. Rules that matter: test `!= null` (never truthiness) so `0` renders; `HH:MM` is `iso.slice(11, 16)` because the string is already destination-local; UV/probability/wind rounded to whole numbers; compass from `windDirection` (`Math.round(deg / 45) % 8` into `N NE E SE S SW W NW`).

```js
const at = (value) => value != null;

export function detailChips(day) {
  const d = day?.details;
  if (!d) return [];
  const chips = [];
  if (d.sunrise || d.sunset) {
    chips.push({ icon: '🌅', text: [d.sunrise && d.sunrise.slice(11, 16), d.sunset && d.sunset.slice(11, 16)]
      .filter(Boolean).join(' · '), title: 'Sunrise · sunset, local time' });
  }
  if (at(d.uvIndexMax)) chips.push({ icon: '☀️', text: `UV ${Math.round(d.uvIndexMax)}`, title: 'Peak UV index' });
  if (at(d.precipitationProbabilityMax)) {
    chips.push({ icon: '💧', text: `${Math.round(d.precipitationProbabilityMax)}%`, title: 'Chance of rain' });
  }
  if (at(d.windSpeedMax)) {
    chips.push({ icon: '💨', text: `${Math.round(d.windSpeedMax)} km/h`,
      title: at(d.windGustsMax) ? `Gusts up to ${Math.round(d.windGustsMax)} km/h` : 'Top wind speed' });
  }
  if (at(d.apparentTemperatureMax) || at(d.apparentTemperatureMin)) {
    chips.push({ icon: '🌡', text: `${Math.round(d.apparentTemperatureMax ?? d.apparentTemperatureMin)}°`
      + (at(d.apparentTemperatureMax) && at(d.apparentTemperatureMin) ? ` / ${Math.round(d.apparentTemperatureMin)}°` : ''),
      title: 'Feels like' });
  }
  return chips;
}
```
and `detailsTitle` joins the remaining present values into one `·`-separated sentence (`Rain 0.7 mm · Snow 0 cm · Humidity 79% · Cloud cover 78% · Daylight 12h 31m · Wind from NE`), each part only if present.

- [ ] **Step 2: Wire and render.** In `itinerary.js` expose `weatherDetails: (day) => detailChips(day)` and `weatherDetailsTitle: (day) => detailsTitle(day)`. In `trip.html`, inside the card and only when a reading exists, add below the `weather-line`:
```html
<div class="weather-details tiny muted" x-show="weatherDetails(row.day).length"
     :title="weatherDetailsTitle(row.day)">
  <template x-for="chip in weatherDetails(row.day)" :key="chip.icon">
    <span class="weather-detail" :title="chip.title" x-text="chip.icon + ' ' + chip.text"></span>
  </template>
</div>
```
Style `.weather-details` as a wrapping flex row with a small gap, in the same file as `.weather-line`; then `cd planner-web && npm run css`.

- [ ] **Step 3: Verify in the browser.** Serve (`cd planner-web && ./serve.sh`, API on :8080), open a trip whose destination covers today, and check with `getBoundingClientRect().height > 0` (not `hidden`, per the CLAUDE.md trap): today's card shows sunrise/sunset, UV, 0 %, wind and feels-like; a far-future day (climate) shows sunrise/sunset and wind but **no** UV, probability or feels-like; a day with no details (an old cached record) looks exactly as before. Console: `import('/js/weather.js').then(m => console.log(m.detailChips({details:{precipitationProbabilityMax:0}})))` → one chip reading `0%`.

- [ ] **Step 4: Commit**

```bash
git add planner-web/js planner-web/scss planner-web/trip.html
git commit -m "feat(itinerary): show sunrise, sunset, UV, rain chance, wind and feels-like on weather cards"
```

---

### Task 6: Published page and the drift guard

**Files:**
- Modify: `planner-api/src/main/resources/publish/page.js` (the `daily=` at ~818, the parsing at ~836-845, `render` at ~849)
- Modify: `planner-api/src/main/resources/publish/page.css` (the details row — `page.css` needs its own copy; it does not use the planner's stylesheet)
- Create: `planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherDetailsDriftTest.java`

**Interfaces:**
- Consumes: `DetailField` (Task 1).

- [ ] **Step 1: Write the drift test first.**

```java
package com.josephinealinea.planner.weather;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * page.js is a static file with its own copy of the request, because it looks
 * the weather up in the reader's browser. If a field is added to DetailField
 * and forgotten there, the planner shows it and a published page silently does
 * not. This fails instead.
 */
class WeatherDetailsDriftTest {

    @Test
    void publishedPageAsksForEveryDetailTheApiDoes() throws IOException {
        String page = new String(getClass().getResourceAsStream("/publish/page.js").readAllBytes(),
                StandardCharsets.UTF_8);
        for (DetailField field : DetailField.values()) {
            assertThat(page).as("page.js should request " + field.parameter()).contains(field.parameter());
            assertThat(page).as("page.js should read key " + field.key()).contains(field.key());
        }
    }

    @Test
    void publishedPageDoesNotAskTheClimateEndpointForForecastOnlyFields() throws IOException {
        String page = new String(getClass().getResourceAsStream("/publish/page.js").readAllBytes(),
                StandardCharsets.UTF_8);
        String climateList = page.substring(page.indexOf("CLIMATE_DAILY"),
                page.indexOf(";", page.indexOf("CLIMATE_DAILY")));
        for (DetailField field : DetailField.values()) {
            if (!field.inClimate()) assertThat(climateList).doesNotContain(field.parameter());
        }
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `cd planner-api && ./gradlew test --tests 'WeatherDetailsDriftTest'`
Expected: FAIL (page.js has none of them).

- [ ] **Step 3: Implement in `page.js`** (ES5 style, `var`, no modules). Replace the inline `'weather_code,temperature_2m_max,...'` strings with two named constants, `FORECAST_DAILY` and `CLIMATE_DAILY`, each the base three or four values plus that endpoint's details, literal (the test reads the file text). In the per-slot loop collect the details the way the API does — a `DETAIL_KEYS` array of `[key, parameter, isText]` triples; skip a column whose value is `null`; keep `0`. Pass `details` to `render(...)`, which appends a `weather-details` row under the temperature line built from the same five chips as `detailChips` (hand-port; keep the labels identical), each only when its key is present. The page's `HORIZON_DAYS = 14` and `< -85` already match the API — leave them.

- [ ] **Step 4: Run to verify pass**, then the publish tests:

Run: `cd planner-api && ./gradlew test --tests 'WeatherDetailsDriftTest' --tests '*Publish*' --tests 'PersonalPageTest'`
Expected: PASS (the self-containment test must still pass: only `api.open-meteo.com` / `climate-api.open-meteo.com` requests, as before).

- [ ] **Step 5: Verify in a real page.** Publish a trip with today in its range, open `/p/<slug>?cb=1` (the page is cached 5 min — see *Traps*; grep the file for `uv_index_max` first to be sure it is the new one). Confirm the same chips as the planner appear and that a far-future day omits UV.

- [ ] **Step 6: Commit**

```bash
git add planner-api/src/main/resources/publish planner-api/src/test/java/com/josephinealinea/planner/weather/WeatherDetailsDriftTest.java
git commit -m "feat(publish): published pages look up and show the same weather details"
```

---

### Task 7: Documentation

**Files:**
- Modify: `CLAUDE.md` (the *Open-Meteo (weather)* section) and `README.md` only if it lists `app.weather.*` settings.

- [ ] **Step 1:** Add to the Open-Meteo section, in the section's own voice:
  - `details` is one JSON document per record (`jsonb`, V11), keys are `DetailField.key()`, **absent means unknown and 0 is a real answer**; an old record has none and is filled only when its TTL lapses.
  - `/climate` is only asked for fields it answers (list under *Verified facts* above); UV, feels-like, rain probability, wind direction are forecast-only.
  - `timezone=auto` is required on **both** calls or sunrise/sunset are UTC.
  - `app.weather.today.cache-ttl` (default 12 h) governs today's date; `app.weather.cache-ttl` governs later days; past days are never refetched.
  - `WeatherDetailsDriftTest` guards `page.js` against `DetailField`.
  - Air quality was deliberately left out: separate host, hourly, forecast-only.
- [ ] **Step 2:** In `application.yml` the `today.cache-ttl` key already carries its explanation from Task 3; add a one-line comment there if it does not.
- [ ] **Step 3: Run the whole suite and check for skips**

Run: `cd planner-api && ./gradlew test`
Expected: BUILD SUCCESSFUL, and `skipped` = 0 across `build/test-results/test/*.xml`.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: weather details, today's cache TTL and the climate endpoint's limits"
```

---

## Self-Review

- **Spec coverage:** extra fields on the same calls (T1); climate limited to fields it answers (T1, verified facts); hide empty fields, keep zero (T1, T5, T6); JSON document (T2); TTL configurable at 12 h (T3); past days never refetched — unchanged rule, pinned by a test (T3); published page parity (T6); air quality skipped (Global Constraints). *Not changed, deliberately:* the "fetch a past date once if no record exists" rule — kept, because a trip entered after the fact has no stored readings.
- **Placeholder scan:** none. Tests in T3/T4 give the scenario and assertions in comments because they reuse `WeatherServiceTest`'s existing clock/store fixtures, which the implementer must read first; the assertions themselves are fully specified.
- **Type consistency:** `DetailField.key()/parameter()/text()/inClimate()`, `Reading.details()`, `WeatherRecord.getDetails/setDetails`, `DayWeather.details`, `TodayWeatherProperties.cacheTtl()` are used with the same names throughout.
- **Known risks:** (1) `TripScopedJdbcRepository`'s new `jsonColumns` overload is the only shared-code change — run all Jdbc contract tests; (2) `Reading` and `DayWeather` gain a component, so every positional construction (tests included) needs updating; (3) old cached records show no details until their TTL lapses, by design.
