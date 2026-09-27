package com.josephinealinea.planner.news;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josephinealinea.planner.news.domain.NewsArticle;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
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

    /** An article whose link isn't http(s) is never shown — an Alpine :href binds it verbatim,
     * so a javascript: link would run script on the app's own origin when clicked. */
    @Test
    void anArticleWithAnUnsafeLinkIsDropped() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"success","results":[
                  {"title":"safe","link":"https://example.com/a","pubDate":null,"source_name":"s"},
                  {"title":"unsafe","link":"javascript:alert(1)","pubDate":null,"source_name":"s"}
                ]}
                """);
        List<NewsArticle> articles = client(http, "key").search("q", "en", 3);
        assertThat(articles).extracting(NewsArticle::title).containsExactly("safe");
    }

    /** An unsafe image URL is dropped, but the article itself is kept — the link is what matters. */
    @Test
    void anArticleWithAnUnsafeImageUrlKeepsTheArticleButDropsTheImage() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"success","results":[
                  {"title":"t","link":"https://example.com/a","image_url":"javascript:alert(1)","pubDate":null,"source_name":"s"}
                ]}
                """);
        List<NewsArticle> articles = client(http, "key").search("q", "en", 3);
        assertThat(articles).hasSize(1);
        assertThat(articles.get(0).imageUrl()).isNull();
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
        // A real failure, not a coincidental empty list — three in a row opens the breaker.
        assertThat(client(new CannedHttp().ok("{\"status\":\"error\"}"), "bad").search("q", "en", 3)).isEmpty();
        assertThat(client(new CannedHttp().ok("{\"status\":\"error\"}"), "bad").search("q", "en", 3)).isEmpty();
        assertThat(breaker.paused(NewsDataClient.SERVICE)).isTrue();
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

    /** Runs the call with the class's log captured; returns every formatted message plus throwable text. */
    private String logged(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger(NewsDataClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
        }
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            all.append(e.getFormattedMessage()).append('\n');
            for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void aFailedCallNeverLogsTheKey() {
        ClientHttpRequestFactory failing = (uri, method) -> {
            throw new IOException("connect timed out for " + uri);
        };
        NewsDataClient c = new NewsDataClient(
                RestClient.builder().baseUrl("https://newsdata.io").requestFactory(failing).build(),
                new NewsProperties(true, 0.9, 3, new NewsProperties.Service("https://newsdata.io", "secret123", 200), null),
                new ApiUsageService(usage), breaker);

        String log = logged(() -> c.search("q", "en", 3));

        assertThat(log).isNotBlank().doesNotContain("secret123").doesNotContain("apikey=secret");
    }
}
