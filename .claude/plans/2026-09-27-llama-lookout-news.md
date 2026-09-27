# Llama Lookout (Destination News) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **No git steps.** The owner writes their own git history and a hook blocks commits. Every task ends with a **Checkpoint** (tests green), never a commit. Do not run `git commit`, `git branch` or `git stash`.

**Goal:** Below the destinations list on the Destinations tab, a carousel — "🦙 Llama Lookout" — shows a few current, place-relevant news stories per destination, sourced from NewsData.io and Currents, with zero server-side storage of any article.

**Architecture:** A new `news/` module in the API: two clients (`NewsDataClient`, `CurrentsClient`) that each gate themselves on a daily quota (reusing the existing `usage/` module with a new day-keyed period) and the existing circuit breaker; a `NewsService` that dedups a trip's destinations, builds a `"<name>" <Country>` query per unique place (reusing `CountryTable`, already loaded from `publish/countries.json`), and alternates providers (NewsData first, then a coin flip) skipping whichever is capped; a member-only `GET /trips/{id}/news` that sets `Cache-Control: private, max-age=86400` so the browser's own HTTP cache is the only cache that exists. The frontend adds one carousel to `js/pages/trip/destinations.js`, fetched when the Destinations tab is shown, shuffling each destination's articles client-side on every fetch.

**Tech Stack:** Spring Boot 3.5 / Java 21, Jackson (manual `JsonNode` parsing, matching `AeroDataBoxClient`), JUnit 5 + AssertJ, Alpine.js, Sass.

**Spec:** `.claude/specs/2026-09-27-llama-lookout-news-design.md`

## Global Constraints

- Query text is always `"<destination.name>" <CountryName>` (quoted place name, unquoted country name), never a bare place name and never `country=` filtering — both were tested and rejected (see spec's "What the probes established").
- `CountryName` comes from `CountryTable` (already loads `publish/countries.json`); a destination with an unrecognised country code just queries the bare quoted name.
- Unique destinations are deduped by `name + countryCode` (case-insensitive), in the trip's own destination order (`DestinationRepository.findAllOrdered`).
- Provider selection per unique destination: the **first** lookup in a request always tries NewsData; every later one is a coin flip. Before calling the chosen provider, skip it if its daily quota is spent and use the other; if both are spent, that destination gets no call at all.
- Daily quota: `NEWSDATA_KEY` daily limit 200, `NEWSCURRENTS_KEY` daily limit 250, effective cap = `floor(limit * cap-fraction)`, `cap-fraction` default `0.9`, all configurable under `app.news`. Once a provider's effective cap is reached for the UTC day, **zero** further calls to it — no exceptions.
- `max-articles-per-destination` configurable, default 3.
- No domain record, no repository, no YAML file, no Postgres table for articles. The endpoint's only persistence-adjacent behaviour is the `Cache-Control` header.
- Language: `LocaleContextHolder.getLocale().getLanguage()` (already resolved per-request by `RequestLocale`), passed as each API's `language=` parameter.
- Both clients use the existing `CircuitBreaker` (`isOpen`/`success`/`failure`) exactly like `AeroDataBoxClient` does.
- A destination whose lookup produced nothing (capped, breaker open, or a genuine empty answer) is simply absent from the response array — never a distinguishable error state.
- Frontend: fetched on showing the Destinations tab (every time, not guarded — the browser's own HTTP cache is what avoids the repeat network cost); articles within each destination group are shuffled client-side after every fetch.
- `planner-api/.env.newscurrents` is missing its `=` (currently `NEWSCURRENTS_KEY'...'`) — fix it in Task 3 or sourcing it silently sets nothing.

## Review Focus

1. **A country code `CountryTable` doesn't recognise** (a hand-typed destination with a stale or malformed code) must still produce a query — the bare quoted name, not a thrown exception or a query containing the literal string "null" (Task 4).
2. **Both providers already at their daily cap** when a request comes in must return an empty array for every destination, not an error, and must make **zero** outbound HTTP calls (Task 6).
3. **Two destinations with the same name in different countries** (e.g. "Cusco" only exists once here, but "Santiago, Chile" and "Santiago, Spain" could both appear) must be treated as genuinely different lookups — dedup key must include country code, not name alone (Task 6).
4. **A provider's JSON parses but its own `status` field says failure** (e.g. NewsData's `"status":"error"` on a bad key, arriving as HTTP 200) must be treated as a failed call (breaker `failure`, empty result), not silently read as "zero articles found" (Task 4, Task 5).
5. **The very first destination in a multi-destination trip** must always try NewsData first, every run, not by chance — this is deterministic, not part of the coin flip (Task 6).

## File Structure

```
planner-api/src/main/java/com/josephinealinea/planner/
  usage/api/ApiUsageService.java              (modify: dayOf, tryAcquireDaily, callsToday)
  geocoding/CountryTable.java                 (modify: nameOf)
  news/
    NewsProperties.java
    NewsConfig.java
    NewsDataClient.java
    CurrentsClient.java
    domain/NewsArticle.java
    domain/DestinationNewsGroup.java
    api/NewsService.java
    web/NewsController.java
planner-api/src/test/java/com/josephinealinea/planner/
  usage/api/ApiUsageServiceTest.java          (modify: daily-period tests)
  geocoding/CountryTableTest.java             (new, or modify if one exists)
  news/NewsPropertiesTest.java
  news/NewsDataClientTest.java
  news/CurrentsClientTest.java
  news/api/NewsServiceTest.java
  news/web/NewsControllerTest.java
planner-api/src/main/resources/
  application.yml                             (modify: app.news block)
planner-api/.env.newscurrents                 (fix missing '=')
planner-web/
  js/api.js                                   (modify: news(id))
  js/pages/trip/destinations.js               (modify: loadNews, shuffled, newsMeta)
  js/i18n/en.js                                (modify: trip.llamaLookout, news.* keys)
  trip.html                                    (modify: carousel markup in #panel-destinations)
  scss/components/_news.scss                   (new)
  scss/_core.scss                              (modify: @use "components/news")
docs/external-apis/
  newsdata.md, currents.md                    (new)
  README.md                                    (modify: two new rows)
```

---

### Task 1: Daily quota on the shared usage counter

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/usage/api/ApiUsageService.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/usage/api/ApiUsageServiceTest.java`

**Interfaces:**
- Consumes: `ApiUsageRepository.tryAcquire(String service, String period, int cap)` / `.calls(String service, String period)` — unchanged, already accept an arbitrary period string.
- Produces: `ApiUsageService.tryAcquireDaily(String service, int cap): boolean`, `ApiUsageService.callsToday(String service): int`, `ApiUsageService.dayOf(Instant): String` (package-private, mirrors `monthOf`) — Task 4 and Task 6 call these.

- [ ] **Step 1: Write the failing tests**

Add to `ApiUsageServiceTest`:

```java
    @Test
    void dayIsTheUtcCalendarDay() {
        assertThat(ApiUsageService.dayOf(Instant.parse("2026-09-27T23:59:59Z"))).isEqualTo("2026-09-27");
        assertThat(ApiUsageService.dayOf(Instant.parse("2026-09-28T00:00:00Z"))).isEqualTo("2026-09-28");
    }

    @Test
    void aNewDayNeedsNoResetJob() {
        Memory memory = new Memory();
        assertThat(new ApiUsageService(memory, at("2026-09-27T23:59:00Z")).tryAcquireDaily("newsdata", 1)).isTrue();
        assertThat(new ApiUsageService(memory, at("2026-09-27T23:59:30Z")).tryAcquireDaily("newsdata", 1)).isFalse();

        ApiUsageService tomorrow = new ApiUsageService(memory, at("2026-09-28T00:00:01Z"));
        assertThat(tomorrow.tryAcquireDaily("newsdata", 1)).isTrue();
        assertThat(tomorrow.callsToday("newsdata")).isEqualTo(1);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'ApiUsageServiceTest'`
Expected: FAIL — `dayOf`, `tryAcquireDaily`, `callsToday` do not exist.

- [ ] **Step 3: Implement**

In `ApiUsageService.java`, add alongside the existing monthly methods:

```java
    /** True when the call may go out; it is counted when granted, even if it then fails. */
    public boolean tryAcquireDaily(String service, int cap) {
        return repository.tryAcquire(service, dayOf(clock.instant()), cap);
    }

    public int callsToday(String service) {
        return repository.calls(service, dayOf(clock.instant()));
    }

    static String dayOf(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return "%04d-%02d-%02d".formatted(utc.getYear(), utc.getMonthValue(), utc.getDayOfMonth());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'ApiUsageServiceTest'`
Expected: PASS

- [ ] **Checkpoint:** `./gradlew test --tests 'ApiUsageServiceTest' --tests 'ApiUsageRepositoryContract*'` all green.

---

### Task 2: Country-code-to-name lookup

**Files:**
- Modify: `planner-api/src/main/java/com/josephinealinea/planner/geocoding/CountryTable.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/geocoding/CountryTableTest.java` (new — check first whether one already exists and extend it instead)

**Interfaces:**
- Produces: `CountryTable.nameOf(String code): String` (null if unrecognised or code is null) — Task 6 uses this to build the query term.

- [ ] **Step 1: Write the failing test**

```java
package com.josephinealinea.planner.geocoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryTableTest {

    @Test
    void namesAKnownCodeCaseInsensitively() {
        assertThat(CountryTable.nameOf("PE")).isEqualTo("Peru");
        assertThat(CountryTable.nameOf("pe")).isEqualTo("Peru");
        assertThat(CountryTable.nameOf(" bo ")).isEqualTo("Bolivia");
    }

    @Test
    void anUnknownOrMissingCodeHasNoName() {
        assertThat(CountryTable.nameOf("ZZ")).isNull();
        assertThat(CountryTable.nameOf(null)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'CountryTableTest'`
Expected: FAIL — `nameOf` does not exist.

- [ ] **Step 3: Implement**

Add to `CountryTable.java`, next to `isKnown`:

```java
    public static String nameOf(String code) {
        return code == null ? null : NAMES.get(code.trim().toUpperCase(Locale.ROOT));
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'CountryTableTest'`
Expected: PASS

- [ ] **Checkpoint:** `./gradlew test --tests 'CountryTableTest'` green; `Locale` import already present from `isKnown`.

---

### Task 3: `NewsProperties` config and `NewsConfig` clients

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/NewsProperties.java`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/NewsConfig.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/news/NewsPropertiesTest.java`
- Modify: `planner-api/src/main/resources/application.yml`
- Modify: `planner-api/.env.newscurrents` (fix the missing `=`)

**Interfaces:**
- Produces: `NewsProperties(boolean enabled, double capFraction, int maxArticlesPerDestination, Service newsdata, Service currents)`, `NewsProperties.Service(String baseUrl, String key, int dailyLimit)` with `.enabled()`, and `NewsProperties.capFor(Service): int`. `NewsConfig` produces beans `newsDataHttp` and `currentsHttp` (`RestClient`) — Task 4 and Task 5 consume both.

- [ ] **Step 1: Fix the env file**

`planner-api/.env.newscurrents` currently reads:

```
NEWSCURRENTS_KEY'Z4E3eKoxtbo6dQGhXot5CWb-eOYIJHd6i-FjzS5-_tB_iGHw'
```

Change it to:

```
NEWSCURRENTS_KEY='Z4E3eKoxtbo6dQGhXot5CWb-eOYIJHd6i-FjzS5-_tB_iGHw'
```

(the missing `=` meant sourcing this file never actually set the variable).

- [ ] **Step 2: Write the failing test**

```java
package com.josephinealinea.planner.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsPropertiesTest {

    @Test
    void defaultsFillInWhenNothingIsConfigured() {
        NewsProperties props = new NewsProperties(true, 0, 0, null, null);

        assertThat(props.capFraction()).isEqualTo(0.9);
        assertThat(props.maxArticlesPerDestination()).isEqualTo(3);
        assertThat(props.newsdata().dailyLimit()).isEqualTo(200);
        assertThat(props.currents().dailyLimit()).isEqualTo(250);
        assertThat(props.newsdata().enabled()).isFalse(); // no key
    }

    @Test
    void capIsTheDailyLimitTimesTheFraction() {
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));

        assertThat(props.capFor(props.newsdata())).isEqualTo(180);
        assertThat(props.capFor(props.currents())).isEqualTo(225);
    }

    @Test
    void aServiceWithAKeyIsEnabled() {
        NewsProperties.Service service = new NewsProperties.Service("https://newsdata.io", "k", 200);
        assertThat(service.enabled()).isTrue();
        assertThat(new NewsProperties.Service("https://newsdata.io", null, 200).enabled()).isFalse();
        assertThat(new NewsProperties.Service("https://newsdata.io", " ", 200).enabled()).isFalse();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'NewsPropertiesTest'`
Expected: FAIL — `NewsProperties` does not exist.

- [ ] **Step 4: Implement `NewsProperties`**

```java
package com.josephinealinea.planner.news;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Configuration;

/**
 * Everything the news module can be told, under {@code app.news}, in a record
 * of its own rather than a component of AppProperties (a score of tests
 * construct that positionally). A missing key on either service means that
 * service is off, not that the app fails to start — see {@link Service#enabled()}.
 */
@ConfigurationProperties(prefix = "app.news")
public record NewsProperties(boolean enabled, double capFraction, int maxArticlesPerDestination,
                             Service newsdata, Service currents) {

    @ConstructorBinding
    public NewsProperties {
        if (capFraction <= 0) capFraction = 0.9;
        if (maxArticlesPerDestination <= 0) maxArticlesPerDestination = 3;
        if (newsdata == null) newsdata = new Service("https://newsdata.io", null, 200);
        if (currents == null) currents = new Service("https://api.currentsapi.services", null, 250);
    }

    /** The most calls a UTC day may spend against this service. */
    public int capFor(Service service) {
        return (int) Math.floor(service.dailyLimit() * capFraction);
    }

    /** One outside service: where it is, its key and its free-tier daily limit. */
    public record Service(String baseUrl, String key, int dailyLimit) {

        public boolean enabled() {
            return key != null && !key.isBlank();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NewsProperties.class)
    public static class Registration {
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'NewsPropertiesTest'`
Expected: PASS

- [ ] **Step 6: Implement `NewsConfig`**

```java
package com.josephinealinea.planner.news;

import com.josephinealinea.planner.shared.HttpCallLog;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Two clients, one per outside service. Named {@code …Http} on purpose: a
 * {@code @Bean RestClient newsDataClient} would collide with the
 * {@code @Service} of the same name and startup would die (see CLAUDE.md).
 */
@Configuration
public class NewsConfig {

    @Bean
    RestClient newsDataHttp(NewsProperties props) {
        return build(props.newsdata(), "NewsData");
    }

    @Bean
    RestClient currentsHttp(NewsProperties props) {
        return build(props.currents(), "Currents");
    }

    private static RestClient build(NewsProperties.Service service, String name) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(8))
                .withReadTimeout(Duration.ofSeconds(8));
        return HttpCallLog.on(RestClient.builder(), name)
                .baseUrl(service.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "travel-planner/1.0")
                .build();
    }
}
```

- [ ] **Step 7: Wire `application.yml`**

Add:

```yaml
app:
  news:
    enabled: true
    cap-fraction: 0.9
    max-articles-per-destination: 3
    newsdata:
      key: ${NEWSDATA_KEY:}
      daily-limit: 200
    currents:
      key: ${NEWSCURRENTS_KEY:}
      daily-limit: 250
```

- [ ] **Checkpoint:** `./gradlew test --tests 'NewsPropertiesTest'` green; `./gradlew compileJava` succeeds (confirms `NewsConfig` compiles against the real `HttpCallLog`/`ClientHttpRequestFactoryBuilder` APIs).

---

### Task 4: News domain records and `NewsDataClient`

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/domain/NewsArticle.java`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/domain/DestinationNewsGroup.java`
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/NewsDataClient.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/news/NewsDataClientTest.java`

**Interfaces:**
- Consumes: `NewsProperties`, `NewsProperties.Service`, `ApiUsageService.tryAcquireDaily`, `CircuitBreaker.isOpen/success/failure`, `com.josephinealinea.planner.weather.CannedHttp` (test only).
- Produces: `NewsArticle(String title, String description, String url, String imageUrl, String sourceName, Instant publishedAt)`, `DestinationNewsGroup(String destinationName, String countryCode, String countryFlag, List<NewsArticle> articles)`, `NewsDataClient.SERVICE = "newsdata"`, `NewsDataClient.search(String query, String languageCode, int limit): List<NewsArticle>` — Task 6 (`NewsService`) calls this.

- [ ] **Step 1: Write the domain records**

`domain/NewsArticle.java`:

```java
package com.josephinealinea.planner.news.domain;

import java.time.Instant;

/** One story. No full body ships on either provider's free plan — a title, a
 * snippet, an image and a link out is all there is. */
public record NewsArticle(String title, String description, String url, String imageUrl,
                          String sourceName, Instant publishedAt) {}
```

`domain/DestinationNewsGroup.java`:

```java
package com.josephinealinea.planner.news.domain;

import java.util.List;

/** One destination's stories. Absent from the response entirely if empty —
 * see NewsService. */
public record DestinationNewsGroup(String destinationName, String countryCode, String countryFlag,
                                   List<NewsArticle> articles) {}
```

- [ ] **Step 2: Write the failing test**

```java
package com.josephinealinea.planner.news;

import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsDataClientTest {

    /** Real payload shape, trimmed to the fields the app reads. */
    private static final String CUSCO = """
            {"status":"success","totalResults":2,"results":[
              {"title":"Concerns grow for missing Australian hiker in Peru",
               "link":"https://www.nine.com.au/world-news/jordan-yap-australia-hiker-missing-peru",
               "description":"A Melbourne hiker has not been heard from.",
               "pubDate":"2026-09-26 22:54:49","source_name":"9news","image_url":"https://img/1.jpg"},
              {"title":"Giant statue of Pope Leo XIV takes shape in Peru workshop",
               "link":"https://www.bangkokpost.com/world/pope-statue",
               "description":"A workshop in Cusco is building a giant statue.",
               "pubDate":"2026-09-26 14:45:00","source_name":"Bangkok Post","image_url":null}
            ]}
            """;

    private static final class Counting implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String period, int cap) {
            String key = service + "|" + period;
            int now = counts.getOrDefault(key, 0);
            if (now >= cap) return false;
            counts.put(key, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String period) {
            return counts.getOrDefault(service + "|" + period, 0);
        }
    }

    private final Counting usage = new Counting();
    private final CircuitBreaker breaker =
            new CircuitBreaker(CircuitBreakerProperties.defaults(), java.time.Clock.systemUTC());

    private NewsDataClient client(CannedHttp http, String key) {
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", key, 200), null);
        return new NewsDataClient(http.client(), props, new ApiUsageService(usage), breaker);
    }

    @Test
    void readsArticlesFromASuccessfulSearch() {
        CannedHttp http = new CannedHttp().ok(CUSCO);

        List<NewsArticle> articles = client(http, "key").search("\"Cusco\" Peru", "en", 3);

        assertThat(articles).hasSize(2);
        assertThat(articles.get(0).title()).isEqualTo("Concerns grow for missing Australian hiker in Peru");
        assertThat(articles.get(0).sourceName()).isEqualTo("9news");
        assertThat(articles.get(0).url()).isEqualTo("https://www.nine.com.au/world-news/jordan-yap-australia-hiker-missing-peru");
        assertThat(articles.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-26T22:54:49Z"));
        assertThat(articles.get(1).imageUrl()).isNull();
        assertThat(http.asked().get(0).toString()).contains("/api/1/latest").contains("q=");
    }

    @Test
    void aLimitCapsHowManyAreReturned() {
        assertThat(client(new CannedHttp().ok(CUSCO), "key").search("q", "en", 1)).hasSize(1);
    }

    @Test
    void noKeyMeansNoCallAtAll() {
        CannedHttp http = new CannedHttp();
        assertThat(client(http, null).search("q", "en", 3)).isEmpty();
        assertThat(http.callCount()).isZero();
    }

    /** A 200 whose own status field says "error" (a bad key, a rate limit) is a failure, not zero results. */
    @Test
    void aBodyLevelErrorStatusIsNotReadAsEmpty() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"error","results":{"message":"Invalid API key","code":"Unauthorized"}}
                """);
        assertThat(client(http, "bad").search("q", "en", 3)).isEmpty();
    }

    @Test
    void aTimeoutOrErrorReturnsEmptyRatherThanThrowing() {
        CannedHttp http = new CannedHttp().status(500, "");
        assertThat(client(http, "key").search("q", "en", 3)).isEmpty();
    }

    @Test
    void onceTheDailyCapIsReachedNoFurtherCallGoesOut() {
        CannedHttp http = new CannedHttp().ok(CUSCO);
        NewsDataClient onlyOne = new NewsDataClient(http.client(),
                new NewsProperties(true, 1.0, 3, new NewsProperties.Service("https://newsdata.io", "key", 1), null),
                new ApiUsageService(usage), breaker);

        assertThat(onlyOne.search("first", "en", 3)).hasSize(2);
        assertThat(onlyOne.search("second", "en", 3)).isEmpty();
        assertThat(http.callCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'NewsDataClientTest'`
Expected: FAIL — `NewsDataClient` does not exist.

- [ ] **Step 4: Implement `NewsDataClient`**

```java
package com.josephinealinea.planner.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * NewsData.io (https://newsdata.io/api/1/latest). Free plan, 200 requests a
 * day install-wide. Every call here is a place-name search (<code>q=</code>),
 * qualified with the country name by the caller — see NewsService. Filtering
 * by <code>country=</code> was tested and rejected: it returns generic wire
 * stories tagged with dozens of countries at once (see the design spec).
 */
@Service
public class NewsDataClient {

    public static final String SERVICE = "newsdata";

    private static final Logger log = LoggerFactory.getLogger(NewsDataClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PUB_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record Raw(int status, String body) {}

    private final RestClient http;
    private final NewsProperties.Service config;
    private final NewsProperties props;
    private final ApiUsageService usage;
    private final CircuitBreaker breaker;

    public NewsDataClient(RestClient newsDataHttp, NewsProperties props, ApiUsageService usage,
                          CircuitBreaker breaker) {
        this.http = newsDataHttp;
        this.config = props.newsdata();
        this.props = props;
        this.usage = usage;
        this.breaker = breaker;
    }

    /** Up to {@code limit} articles, oldest quietly dropped. Never throws; a
     * failure of any kind — disabled, paused, capped, unreadable, or a
     * body-level error status — answers with an empty list. */
    public List<NewsArticle> search(String query, String languageCode, int limit) {
        if (!config.enabled()) return List.of();
        if (breaker.isOpen(SERVICE)) {
            log.debug("NewsData is paused by the circuit breaker; not calling");
            return List.of();
        }
        int cap = props.capFor(config);
        if (!usage.tryAcquireDaily(SERVICE, cap)) {
            log.info("NewsData daily cap ({}) reached; not calling", cap);
            return List.of();
        }
        Raw raw = ask(query, languageCode);
        List<NewsArticle> articles = interpret(raw, limit);
        if (articles == null) {
            breaker.failure(SERVICE);
            return List.of();
        }
        breaker.success(SERVICE);
        return articles;
    }

    private Raw ask(String query, String languageCode) {
        try {
            return http.get()
                    .uri(uri -> uri.path("/api/1/latest")
                            .queryParam("apikey", config.key())
                            .queryParam("q", query)
                            .queryParam("language", languageCode)
                            .build())
                    .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("NewsData search for {} failed: {}", query, e.getMessage());
            return new Raw(0, null);
        }
    }

    /** Null means "treat as a failure" — an unreadable response or one whose
     * own status field says error, never confused with a genuine zero results. */
    private static List<NewsArticle> interpret(Raw raw, int limit) {
        if (raw.status() != 200 || raw.body() == null) {
            log.warn("NewsData answered {}", raw.status());
            return null;
        }
        try {
            JsonNode root = JSON.readTree(raw.body());
            if (!"success".equals(text(root, "status"))) {
                log.warn("NewsData answered a non-success status");
                return null;
            }
            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                if (articles.size() >= limit) break;
                articles.add(new NewsArticle(
                        text(item, "title"), text(item, "description"), text(item, "link"),
                        text(item, "image_url"), text(item, "source_name"),
                        publishedAt(text(item, "pubDate"))));
            }
            return articles;
        } catch (Exception e) {
            log.warn("NewsData answered unreadable JSON: {}", e.getMessage());
            return null;
        }
    }

    private static Instant publishedAt(String pubDate) {
        if (pubDate == null) return null;
        try {
            return LocalDateTime.parse(pubDate, PUB_DATE).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'NewsDataClientTest'`
Expected: PASS

- [ ] **Checkpoint:** `./gradlew test --tests 'NewsDataClientTest'` green.

---

### Task 5: `CurrentsClient`

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/CurrentsClient.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/news/CurrentsClientTest.java`

**Interfaces:**
- Consumes: same as Task 4 (`NewsProperties`, `ApiUsageService`, `CircuitBreaker`, `CannedHttp`).
- Produces: `CurrentsClient.SERVICE = "currents"`, `CurrentsClient.search(String query, String languageCode, int limit): List<NewsArticle>` — Task 6 calls this exactly like `NewsDataClient.search`.

- [ ] **Step 1: Write the failing test**

```java
package com.josephinealinea.planner.news;

import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentsClientTest {

    private static final String LA_PAZ = """
            {"status":"ok","news":[
              {"title":"Bolivia has rejected socialism and is open for business",
               "url":"https://andina.pe/agencia/noticia-1",
               "description":"President Paz reaffirmed the shift.",
               "published":"2026-09-26 05:15:00 +0000","author":"Andina","image":"https://img/2.jpg"}
            ]}
            """;

    private static final class Counting implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String period, int cap) {
            String key = service + "|" + period;
            int now = counts.getOrDefault(key, 0);
            if (now >= cap) return false;
            counts.put(key, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String period) {
            return counts.getOrDefault(service + "|" + period, 0);
        }
    }

    private final Counting usage = new Counting();
    private final CircuitBreaker breaker =
            new CircuitBreaker(CircuitBreakerProperties.defaults(), java.time.Clock.systemUTC());

    private CurrentsClient client(CannedHttp http, String key) {
        NewsProperties props = new NewsProperties(true, 0.9, 3, null,
                new NewsProperties.Service("https://api.currentsapi.services", key, 250));
        return new CurrentsClient(http.client(), props, new ApiUsageService(usage), breaker);
    }

    @Test
    void readsArticlesFromASuccessfulSearch() {
        List<NewsArticle> articles = client(new CannedHttp().ok(LA_PAZ), "key")
                .search("\"La Paz\" Bolivia", "en", 3);

        assertThat(articles).hasSize(1);
        assertThat(articles.get(0).sourceName()).isEqualTo("Andina");
        assertThat(articles.get(0).publishedAt()).isEqualTo(Instant.parse("2026-09-26T05:15:00Z"));
    }

    @Test
    void aNonOkStatusIsNotReadAsEmpty() {
        CannedHttp http = new CannedHttp().status(400, """
                {"status":"400","msg":"Bad request"}
                """);
        assertThat(client(http, "key").search("q", "en", 3)).isEmpty();
    }

    @Test
    void onceTheDailyCapIsReachedNoFurtherCallGoesOut() {
        CannedHttp http = new CannedHttp().ok(LA_PAZ);
        CurrentsClient onlyOne = new CurrentsClient(http.client(),
                new NewsProperties(true, 1.0, 3, null,
                        new NewsProperties.Service("https://api.currentsapi.services", "key", 1)),
                new ApiUsageService(usage), breaker);

        assertThat(onlyOne.search("first", "en", 3)).hasSize(1);
        assertThat(onlyOne.search("second", "en", 3)).isEmpty();
        assertThat(http.callCount()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'CurrentsClientTest'`
Expected: FAIL — `CurrentsClient` does not exist.

- [ ] **Step 3: Implement**

```java
package com.josephinealinea.planner.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Currents (https://api.currentsapi.services/v1/search). Free plan, 250
 * requests a day install-wide. Same place-name-plus-country query discipline
 * as NewsData — see NewsService — and the same rejection of country-level
 * filtering, which this API doesn't even allow to batch (a 400 on a
 * comma-separated country list).
 */
@Service
public class CurrentsClient {

    public static final String SERVICE = "currents";

    private static final Logger log = LoggerFactory.getLogger(CurrentsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PUBLISHED = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    private record Raw(int status, String body) {}

    private final RestClient http;
    private final NewsProperties.Service config;
    private final NewsProperties props;
    private final ApiUsageService usage;
    private final CircuitBreaker breaker;

    public CurrentsClient(RestClient currentsHttp, NewsProperties props, ApiUsageService usage,
                          CircuitBreaker breaker) {
        this.http = currentsHttp;
        this.config = props.currents();
        this.props = props;
        this.usage = usage;
        this.breaker = breaker;
    }

    public List<NewsArticle> search(String query, String languageCode, int limit) {
        if (!config.enabled()) return List.of();
        if (breaker.isOpen(SERVICE)) {
            log.debug("Currents is paused by the circuit breaker; not calling");
            return List.of();
        }
        int cap = props.capFor(config);
        if (!usage.tryAcquireDaily(SERVICE, cap)) {
            log.info("Currents daily cap ({}) reached; not calling", cap);
            return List.of();
        }
        Raw raw = ask(query, languageCode);
        List<NewsArticle> articles = interpret(raw, limit);
        if (articles == null) {
            breaker.failure(SERVICE);
            return List.of();
        }
        breaker.success(SERVICE);
        return articles;
    }

    private Raw ask(String query, String languageCode) {
        try {
            return http.get()
                    .uri(uri -> uri.path("/v1/search")
                            .queryParam("apiKey", config.key())
                            .queryParam("keywords", query)
                            .queryParam("language", languageCode)
                            .build())
                    .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                            new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.warn("Currents search for {} failed: {}", query, e.getMessage());
            return new Raw(0, null);
        }
    }

    private static List<NewsArticle> interpret(Raw raw, int limit) {
        if (raw.status() != 200 || raw.body() == null) {
            log.warn("Currents answered {}", raw.status());
            return null;
        }
        try {
            JsonNode root = JSON.readTree(raw.body());
            if (!"ok".equals(text(root, "status"))) {
                log.warn("Currents answered a non-ok status");
                return null;
            }
            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode item : root.path("news")) {
                if (articles.size() >= limit) break;
                articles.add(new NewsArticle(
                        text(item, "title"), text(item, "description"), text(item, "url"),
                        text(item, "image"), text(item, "author"),
                        publishedAt(text(item, "published"))));
            }
            return articles;
        } catch (Exception e) {
            log.warn("Currents answered unreadable JSON: {}", e.getMessage());
            return null;
        }
    }

    private static Instant publishedAt(String published) {
        if (published == null) return null;
        try {
            return OffsetDateTime.parse(published, PUBLISHED).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'CurrentsClientTest'`
Expected: PASS

- [ ] **Checkpoint:** `./gradlew test --tests 'CurrentsClientTest'` green.

---

### Task 6: `NewsService` — dedup, query text, provider selection

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/api/NewsService.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/news/api/NewsServiceTest.java`

**Interfaces:**
- Consumes: `DestinationRepository.findAllOrdered(String tripSlug): List<Destination>`, `TripAccessService.requireMember(String tripId, String userId): Trip` (`trip.getSlug()`), `CountryTable.nameOf`, `NewsDataClient.search`/`CurrentsClient.search`, `ApiUsageService.callsToday`, `NewsProperties.capFor`.
- Produces: `NewsService.newsFor(String tripId, String userId, String languageCode): List<DestinationNewsGroup>` — Task 7 (`NewsController`) calls this.

- [ ] **Step 1: Write the failing tests**

```java
package com.josephinealinea.planner.news.api;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.news.CurrentsClient;
import com.josephinealinea.planner.news.NewsDataClient;
import com.josephinealinea.planner.news.NewsProperties;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewsServiceTest {

    private static Destination destination(String name, String code, String flag) {
        Destination d = new Destination();
        d.setId(name);
        d.setName(name);
        d.setCountryCode(code);
        d.setCountryFlag(flag);
        return d;
    }

    private static NewsArticle article(String title) {
        return new NewsArticle(title, "desc", "https://x/" + title, null, "Source", null);
    }

    @Test
    void queriesEachUniqueDestinationOnceAndSkipsDuplicates() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("peru-2026");
        when(access.requireMember("trip-1", "user-1")).thenReturn(trip);
        when(destinations.findAllOrdered("peru-2026")).thenReturn(List.of(
                destination("Cusco", "PE", "🇵🇪"),
                destination("cusco", "pe", "🇵🇪"), // same place, different case
                destination("La Paz", "BO", "🇧🇴")));
        when(usage.callsToday(anyString())).thenReturn(0);
        // First lookup always tries NewsData first (Cusco); the second unique
        // destination (La Paz) is whichever the coin flip picks — stub both.
        when(newsData.search(eq("\"Cusco\" Peru"), eq("en"), eq(3))).thenReturn(List.of(article("Cusco story")));
        when(newsData.search(eq("\"La Paz\" Bolivia"), eq("en"), eq(3))).thenReturn(List.of(article("La Paz story A")));
        when(currents.search(eq("\"La Paz\" Bolivia"), eq("en"), eq(3))).thenReturn(List.of(article("La Paz story B")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage, new Random(1));
        List<DestinationNewsGroup> groups = service.newsFor("trip-1", "user-1", "en");

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).destinationName()).isEqualTo("Cusco");
        assertThat(groups.get(0).articles()).extracting(NewsArticle::title).containsExactly("Cusco story");
        assertThat(groups.get(1).destinationName()).isEqualTo("La Paz");
        // Cusco is the very first lookup: it must have gone to NewsData, never Currents.
        verify(newsData, times(1)).search(eq("\"Cusco\" Peru"), anyString(), anyInt());
        verify(currents, never()).search(eq("\"Cusco\" Peru"), anyString(), anyInt());
    }

    @Test
    void aDestinationWithNoCountryCodeQueriesTheBareName() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Somewhere", null, null)));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(eq("\"Somewhere\""), anyString(), anyInt())).thenReturn(List.of(article("A")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage, new Random(1));
        assertThat(service.newsFor("t", "u", "en")).hasSize(1);
        verify(newsData).search(eq("\"Somewhere\""), eq("en"), eq(3));
    }

    @Test
    void aDestinationWithNoArticlesFromEitherProviderIsAbsent() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));
        ApiUsageService usage = mock(ApiUsageService.class);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Nowhere", "ZZ", null)));
        when(usage.callsToday(anyString())).thenReturn(0);
        when(newsData.search(anyString(), anyString(), anyInt())).thenReturn(List.of());

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage, new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
    }

    @Test
    void bothProvidersAtTheirCapMakesNoOutboundCallAtAll() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(true, 1.0, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 1),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 1));
        ApiUsageService usage = mock(ApiUsageService.class);
        when(usage.callsToday(NewsDataClient.SERVICE)).thenReturn(1); // already at cap 1
        when(usage.callsToday(CurrentsClient.SERVICE)).thenReturn(1);

        Trip trip = new Trip();
        trip.setSlug("slug");
        when(access.requireMember("t", "u")).thenReturn(trip);
        when(destinations.findAllOrdered("slug")).thenReturn(List.of(destination("Cusco", "PE", "🇵🇪")));

        NewsService service = new NewsService(destinations, access, newsData, currents, props, usage, new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
        verifyNoInteractions(newsData, currents);
    }

    @Test
    void newsIsEmptyOutrightWhenTheFeatureIsDisabled() {
        DestinationRepository destinations = mock(DestinationRepository.class);
        TripAccessService access = mock(TripAccessService.class);
        NewsDataClient newsData = mock(NewsDataClient.class);
        CurrentsClient currents = mock(CurrentsClient.class);
        NewsProperties props = new NewsProperties(false, 0.9, 3, null, null);

        NewsService service = new NewsService(destinations, access, newsData, currents, props,
                mock(ApiUsageService.class), new Random(1));
        assertThat(service.newsFor("t", "u", "en")).isEmpty();
        verifyNoInteractions(destinations, access, newsData, currents);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd planner-api && ./gradlew test --tests 'NewsServiceTest'`
Expected: FAIL — `NewsService` does not exist.

- [ ] **Step 3: Implement**

```java
package com.josephinealinea.planner.news.api;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryTable;
import com.josephinealinea.planner.news.CurrentsClient;
import com.josephinealinea.planner.news.NewsDataClient;
import com.josephinealinea.planner.news.NewsProperties;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Below the destinations list: a place-relevant story or two per unique
 * destination, no server-side storage. See the design spec
 * (.claude/specs/2026-09-27-llama-lookout-news-design.md) for why the query is
 * always a quoted place name plus the country name, why there is no merging
 * across providers, and why a capped provider makes no call at all.
 */
@Service
public class NewsService {

    private final DestinationRepository destinations;
    private final TripAccessService access;
    private final NewsDataClient newsData;
    private final CurrentsClient currents;
    private final NewsProperties props;
    private final ApiUsageService usage;
    private final Random random;

    @Autowired // two constructors: Spring must be told which one is real
    public NewsService(DestinationRepository destinations, TripAccessService access, NewsDataClient newsData,
                       CurrentsClient currents, NewsProperties props, ApiUsageService usage) {
        this(destinations, access, newsData, currents, props, usage, new Random());
    }

    NewsService(DestinationRepository destinations, TripAccessService access, NewsDataClient newsData,
               CurrentsClient currents, NewsProperties props, ApiUsageService usage, Random random) {
        this.destinations = destinations;
        this.access = access;
        this.newsData = newsData;
        this.currents = currents;
        this.props = props;
        this.usage = usage;
        this.random = random;
    }

    public List<DestinationNewsGroup> newsFor(String tripId, String userId, String languageCode) {
        if (!props.enabled()) return List.of();

        Trip trip = access.requireMember(tripId, userId);
        List<Destination> ordered = destinations.findAllOrdered(trip.getSlug());

        Map<String, Destination> unique = new LinkedHashMap<>();
        for (Destination d : ordered) unique.putIfAbsent(dedupeKey(d), d);

        List<DestinationNewsGroup> groups = new ArrayList<>();
        boolean first = true;
        for (Destination destination : unique.values()) {
            List<NewsArticle> articles = lookup(queryFor(destination), languageCode, first);
            first = false;
            if (!articles.isEmpty()) {
                groups.add(new DestinationNewsGroup(destination.getName(), destination.getCountryCode(),
                        destination.getCountryFlag(), articles));
            }
        }
        return groups;
    }

    private static String dedupeKey(Destination d) {
        String name = d.getName() == null ? "" : d.getName().trim().toLowerCase(Locale.ROOT);
        String code = d.getCountryCode() == null ? "" : d.getCountryCode().trim().toUpperCase(Locale.ROOT);
        return name + "|" + code;
    }

    private static String queryFor(Destination d) {
        String name = d.getName() == null ? "" : d.getName().trim();
        String country = CountryTable.nameOf(d.getCountryCode());
        return country == null ? "\"" + name + "\"" : "\"" + name + "\" " + country;
    }

    /** The first lookup in the batch always tries NewsData; every later one is
     * a coin flip. Either way, a capped provider is swapped for the other one
     * before it is ever asked, and if both are capped the destination gets no
     * call at all. */
    private List<NewsArticle> lookup(String query, String languageCode, boolean preferNewsData) {
        boolean tryNewsDataFirst = preferNewsData || random.nextBoolean();
        String chosen = tryNewsDataFirst ? NewsDataClient.SERVICE : CurrentsClient.SERVICE;
        String other = tryNewsDataFirst ? CurrentsClient.SERVICE : NewsDataClient.SERVICE;
        if (capped(chosen)) chosen = other;
        if (capped(chosen)) return List.of(); // both providers are spent for today

        int limit = props.maxArticlesPerDestination();
        return chosen.equals(NewsDataClient.SERVICE)
                ? newsData.search(query, languageCode, limit)
                : currents.search(query, languageCode, limit);
    }

    private boolean capped(String service) {
        NewsProperties.Service config = service.equals(NewsDataClient.SERVICE) ? props.newsdata() : props.currents();
        return !config.enabled() || usage.callsToday(service) >= props.capFor(config);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'NewsServiceTest'`
Expected: PASS

- [ ] **Checkpoint:** `./gradlew test --tests 'NewsServiceTest'` green. Mockito is already a test dependency (used in `FlightControllerTest`, `AviationStackClientTest`, etc.), so mocking the two concrete client classes directly, as above, is consistent with existing tests — no need for an extracted interface.

---

### Task 7: `NewsController`

**Files:**
- Create: `planner-api/src/main/java/com/josephinealinea/planner/news/web/NewsController.java`
- Test: `planner-api/src/test/java/com/josephinealinea/planner/news/web/NewsControllerTest.java`

**Interfaces:**
- Consumes: `NewsService.newsFor`, `CurrentUserContext.userId()`, `LocaleContextHolder.getLocale()`.
- Produces: `GET /api/v1/trips/{tripId}/news` — Task 8 (frontend `api.js`) calls this.

- [ ] **Step 1: Write the failing test**

Mirrors `flights/web/FlightControllerTest`'s `MockMvcBuilders.standaloneSetup(...)` pattern exactly, mocking `NewsService` directly (one dependency, unlike the flights controller which assembles real clients):

```java
package com.josephinealinea.planner.news.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.identity.api.CurrentUser;
import com.josephinealinea.planner.news.api.NewsService;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NewsControllerTest {

    private final NewsService service = Mockito.mock(NewsService.class);

    private MockMvc mvc() {
        CurrentUserContext user = new CurrentUserContext();
        user.set(new CurrentUser("u1", "a@b.c", "A", false, null));
        return MockMvcBuilders.standaloneSetup(new NewsController(service, user))
                .setControllerAdvice(new GlobalExceptionHandler(I18nConfig.standalone()))
                .build();
    }

    @Test
    void answersTheGroupsTheServiceReturnsWithADayLongPrivateCache() throws Exception {
        Mockito.when(service.newsFor(eq("trip-1"), any(), any())).thenReturn(List.of(
                new DestinationNewsGroup("Cusco", "PE", "🇵🇪",
                        List.of(new NewsArticle("t", "d", "https://x", null, "s", null)))));

        mvc().perform(get("/api/v1/trips/trip-1/news"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=86400, private"))
                .andExpect(jsonPath("$[0].destinationName").value("Cusco"))
                .andExpect(jsonPath("$[0].articles[0].title").value("t"));
    }

    @Test
    void anEmptyResultIsAnEmptyArrayNotAnError() throws Exception {
        Mockito.when(service.newsFor(eq("trip-1"), any(), any())).thenReturn(List.of());

        mvc().perform(get("/api/v1/trips/trip-1/news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
```

- [ ] **Step 2: Implement**

```java
package com.josephinealinea.planner.news.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.news.api.NewsService;
import com.josephinealinea.planner.news.domain.DestinationNewsGroup;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Member-only: below the Destinations list. No server-side cache — the
 * browser's own HTTP cache is the only one, via the header below. See the
 * design spec for why.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/news")
public class NewsController {

    private final NewsService news;
    private final CurrentUserContext currentUser;

    public NewsController(NewsService news, CurrentUserContext currentUser) {
        this.news = news;
        this.currentUser = currentUser;
    }

    @GetMapping
    ResponseEntity<List<DestinationNewsGroup>> news(@PathVariable String tripId) {
        String language = LocaleContextHolder.getLocale().getLanguage();
        List<DestinationNewsGroup> groups = news.newsFor(tripId, currentUser.userId(), language);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(24)).cachePrivate())
                .body(groups);
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

Run: `cd planner-api && ./gradlew test --tests 'NewsControllerTest'`
Expected: PASS once wired to match the codebase's existing controller-test pattern.

- [ ] **Checkpoint:** `./gradlew test` (full suite) green, confirming nothing in `news/` broke Spring's context startup (bean names, `@ConfigurationProperties` registration).

---

### Task 8: Docs — `docs/external-apis/newsdata.md`, `currents.md`, README

**Files:**
- Create: `docs/external-apis/newsdata.md`
- Create: `docs/external-apis/currents.md`
- Modify: `docs/external-apis/README.md`

**Interfaces:** none (documentation only) — this is CLAUDE.md's external-API rule, not optional.

- [ ] **Step 1: Write `docs/external-apis/newsdata.md`**

```markdown
# NewsData.io

Free plan, no paid tier configured. Powers Llama Lookout, the news carousel
below the destinations list on the Destinations tab.

- **Auth:** `apikey` query parameter, `NEWSDATA_KEY` (`app.news.newsdata.key`).
- **Base URL:** `https://newsdata.io`, `app.news.newsdata.base-url`.
- **Call:** `GET /api/1/latest?apikey=&q=&language=`, one call per unique
  destination (`name` + `countryCode`) in a trip. `q` is always
  `"<destination name>" <country name>` — a bare place name is ambiguous (a
  live probe returned Mexico's La Paz, Baja California Sur, for a bare
  `q=La Paz` meant for Bolivia's capital) and `country=` filtering was tested
  and rejected: it returns generic wire-service stories tagged with 20–30
  countries at once, batched or not.
- **Triggered by:** a member opening the Destinations tab.
- **Frequency:** at most once per unique destination per tab-open; the
  frontend relies on the endpoint's own `Cache-Control: private, max-age=86400`
  so most opens cost the browser's HTTP cache, not a real request.
- **Caching:** none, server-side, on purpose. Nothing is written to disk or a
  table; the browser's HTTP cache is the only cache.
- **Daily limit:** 200 requests, install-wide, capped at 90% (180) by default
  (`app.news.cap-fraction`, `app.news.newsdata.daily-limit`). Once the cap is
  reached for the UTC day, zero further calls go out.
- **Failure behaviour:** disabled (no key), circuit-breaker-paused, capped, a
  non-200 response, or a 200 whose own `status` field isn't `"success"` all
  answer with an empty article list for that destination — never an error the
  member sees, and never merged with Currents' results for the same
  destination (see `NewsService`).
- **Quirks:** the free plan strips `content` (`"ONLY AVAILABLE IN PAID
  PLANS"`); only title, description, image, source name, publish date and link
  are used. `image_url` is frequently null.
```

- [ ] **Step 2: Write `docs/external-apis/currents.md`**

```markdown
# Currents

Free plan, no paid tier configured. The second of Llama Lookout's two
providers — see `docs/external-apis/newsdata.md` for the shared design
(neither is "primary"; the first lookup per request tries NewsData, every
later one is a coin flip, whichever isn't capped).

- **Auth:** `apiKey` query parameter, `NEWSCURRENTS_KEY`
  (`app.news.currents.key`).
- **Base URL:** `https://api.currentsapi.services`,
  `app.news.currents.base-url`.
- **Call:** `GET /v1/search?apiKey=&keywords=&language=`, same query
  discipline as NewsData (`"<destination name>" <country name>`).
- **Triggered by / frequency / caching:** identical to NewsData — see that
  file.
- **Daily limit:** 250 requests, install-wide, capped at 90% (225) by default.
- **Failure behaviour:** identical shape to NewsData — empty list, never an
  error, on any failure including a 200 whose `status` isn't `"ok"`.
- **Quirks:** **does not support multi-country batching at all** — a
  comma-separated `country` list (`country=PE,BO`) is a hard `400 Bad
  request`, one more reason country-level filtering was rejected in favour of
  a place-name search. `published` carries an explicit UTC offset
  (`"2026-09-26 05:30:00 +0000"`), unlike NewsData's bare `pubDate`.
```

- [ ] **Step 3: Add two rows to `docs/external-apis/README.md`**

In the table, after the AviationStack row:

```markdown
| [NewsData.io](newsdata.md) | API | `apikey` query parameter (`NEWSDATA_KEY`) | a member opening the Destinations tab | no — browser `Cache-Control` only |
| [Currents](currents.md) | API | `apiKey` query parameter (`NEWSCURRENTS_KEY`) | a member opening the Destinations tab | no — browser `Cache-Control` only |
```

And in "Every call is logged", add "NewsData.io and Currents" to the list of
clients wrapped in `HttpCallLog` (both go through `NewsConfig`, same as
`FlightsConfig`).

- [ ] **Checkpoint:** the three doc files read correctly and the table is well-formed markdown (open in a preview or `mdl`/`markdownlint` if configured).

---

### Task 9: Frontend — carousel on the Destinations tab

**Files:**
- Modify: `planner-web/js/api.js`
- Modify: `planner-web/js/pages/trip/destinations.js`
- Modify: `planner-web/js/pages/trip.js`
- Modify: `planner-web/js/i18n/en.js`
- Modify: `planner-web/trip.html`
- Create: `planner-web/scss/components/_news.scss`
- Modify: `planner-web/scss/_core.scss`

**Interfaces:**
- Consumes: `GET /api/v1/trips/{id}/news` (Task 7).
- Produces: `destinationsTab().loadNews()`, `.newsGroups`, `.newsMeta(article)` — called from `trip.js`'s `showTab`/`init`, per the existing `loadWeather()` wiring pattern.

There is no frontend test suite (per CLAUDE.md) — verify by running the app and looking at it (Step 6).

- [ ] **Step 1: `api.js`**

Add next to the itinerary/weather calls:

```js
  // Below the destinations list. The response carries its own 24h
  // Cache-Control, so most calls here never leave the browser's HTTP cache.
  news: (id) => get(`${trip(id)}/news`),
```

- [ ] **Step 2: `destinations.js` — state and methods**

Add to the returned object (near the top, alongside `destSelectedIds` etc.):

```js
    newsGroups: [],
```

Add a module-level helper above `destinationsTab()`:

```js
function shuffled(list) {
  const copy = [...list];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j], copy[i]];
  }
  return copy;
}
```

Add methods to the returned object:

```js
    /**
     * Fetched every time the Destinations tab is shown, not guarded by a
     * loaded flag: the endpoint's own 24h Cache-Control is what keeps this
     * cheap, so a repeat call within a day costs the browser's cache, not a
     * network round trip. Articles are reshuffled on every call, independent
     * of whether the underlying data came from cache or a fresh fetch.
     */
    async loadNews() {
      try {
        const groups = await this.api.news(this.trip.id);
        this.newsGroups = groups.map((group) => ({ ...group, articles: shuffled(group.articles) }));
      } catch {
        this.newsGroups = [];
      }
    },

    /** "Source · 2h ago" / "Source · 3d ago", falling back to just the source
     * when the article has no publish date. */
    newsMeta(article) {
      if (!article.publishedAt) return article.sourceName || '';
      const hours = Math.max(0, Math.floor((Date.now() - new Date(article.publishedAt).getTime()) / 3600000));
      const when = hours < 1 ? t('news.justNow')
        : hours < 24 ? t('news.hoursAgo', { count: hours })
        : t('news.daysAgo', { count: Math.floor(hours / 24) });
      return article.sourceName ? `${article.sourceName} · ${when}` : when;
    },
```

- [ ] **Step 3: `trip.js` — trigger on tab show**

Find `showTab(id)` (see the existing `if (id === 'itinerary') this.loadWeather();` line) and add a sibling:

```js
      if (id === 'destinations') this.loadNews();
```

Find `init()`, right after `await this.reload();`, add:

```js
      if (this.tab === 'destinations') this.loadNews();
```

(this covers opening the trip directly on the `#destinations` hash, which `showTab` is never called for.)

- [ ] **Step 4: `js/i18n/en.js` — new keys**

Add:

```js
  'trip.llamaLookout': 'Llama Lookout',
  'news.justNow': 'just now',
  'news.hoursAgo.one': '{count}h ago',
  'news.hoursAgo.other': '{count}h ago',
  'news.daysAgo.one': '{count}d ago',
  'news.daysAgo.other': '{count}d ago',
```

(`t()` itself resolves `.one`/`.other` via `Intl.PluralRules` whenever a `count` param is passed — see `budget.deleted.one`/`.other` for the existing convention this follows; no separate plural helper needed.)

- [ ] **Step 5: `trip.html` — carousel markup**

Inside `#panel-destinations`, immediately before its closing `</section>` (after the destinations table's closing `</div>`):

```html
        <div class="news-carousel" x-show="newsGroups.length" x-cloak>
          <div class="section-head">
            <h3 class="section-title" data-i18n="trip.llamaLookout"></h3>
          </div>
          <template x-for="group in newsGroups" :key="group.destinationName + '|' + group.countryCode">
            <div class="news-group">
              <div class="news-group-head">
                <span x-text="group.countryFlag"></span>
                <span x-text="group.destinationName"></span>
              </div>
              <div class="news-row">
                <template x-for="article in group.articles" :key="article.url">
                  <a class="news-card" :href="article.url" target="_blank" rel="noopener">
                    <div class="news-card-image"
                         :style="article.imageUrl ? ('background-image:url(\'' + article.imageUrl + '\')') : ''">
                      <span class="news-card-fallback" x-show="!article.imageUrl">🦙</span>
                    </div>
                    <div class="news-card-body">
                      <div class="news-card-title" x-text="article.title"></div>
                      <div class="news-card-meta" x-text="newsMeta(article)"></div>
                    </div>
                  </a>
                </template>
              </div>
            </div>
          </template>
        </div>
```

- [ ] **Step 6: `scss/components/_news.scss`**

```scss
.news-carousel { margin-top: 20px; }

.news-group { margin-bottom: 16px; }

.news-group-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 8px;
}

.news-row {
  display: flex;
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 4px;
  -webkit-overflow-scrolling: touch;
}

.news-card {
  flex: 0 0 220px;
  border: 1px solid var(--tp-border);
  border-radius: 8px;
  overflow: hidden;
  background: var(--tp-surface);
  color: inherit;
  text-decoration: none;
  display: block;
}

.news-card-image {
  height: 90px;
  background-size: cover;
  background-position: center;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: color-mix(in srgb, var(--tp-border) 40%, transparent);
}

.news-card-fallback { font-size: 24px; }

.news-card-body { padding: 8px; }

.news-card-title {
  font-size: 13px;
  font-weight: 600;
  line-height: 1.3;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.news-card-meta {
  font-size: 11px;
  opacity: 0.65;
  margin-top: 6px;
}
```

Add to `scss/_core.scss`, next to `@use "components/flight";`:

```scss
@use "components/news";
```

- [ ] **Step 7: Rebuild CSS and check manually**

Run: `cd planner-web && npm run css`

Then, with the API running (`cd planner-api && BOOTSTRAP_OWNER_EMAIL=you@example.com BOOTSTRAP_OWNER_PASSWORD=password123 ./gradlew bootRun`, keys sourced from `.env.newsdata`/`.env.newscurrents`) and the frontend served (`cd planner-web && ./serve.sh`):

1. Open a trip with at least two destinations in different countries, on the Destinations tab.
2. Confirm the carousel appears below the destinations table, with cards per destination.
3. Reload the page (bypass cache with a hard reload once, to prove the endpoint answers at all) — then do a normal reload and confirm the Network tab shows the `/news` request served `(disk cache)` or `(memory cache)`, not a new round trip, within the same day.
4. Switch to another tab and back — confirm the card order within a destination's row changes (the shuffle), while the set of destinations shown does not.
5. Click a card — confirm it opens the article in a new tab.

- [ ] **Checkpoint:** manual walkthrough above passes; `npm run check` (i18n check) passes with the new keys.

---

## Deviations to fold back into the spec (Task 10)

After implementing, update `.claude/specs/2026-09-27-llama-lookout-news-design.md` with anything that changed during implementation — in particular any HTTP status code NewsData or Currents were found to answer with in practice that differed from what live probing showed on 27 Sep 2026 (rate-limit responses in particular were not probed live — only inferred from documentation-style `"status":"error"` bodies).
