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

    /** An article whose link isn't http(s) is never shown — an Alpine :href binds it verbatim,
     * so a javascript: link would run script on the app's own origin when clicked. */
    @Test
    void anArticleWithAnUnsafeLinkIsDropped() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"ok","news":[
                  {"title":"safe","url":"https://example.com/a"},
                  {"title":"unsafe","url":"javascript:alert(1)"}
                ]}
                """);
        List<NewsArticle> articles = client(http, "key").search("q", "en", 3);
        assertThat(articles).extracting(NewsArticle::title).containsExactly("safe");
    }

    /** An unsafe image URL is dropped, but the article itself is kept — the link is what matters. */
    @Test
    void anArticleWithAnUnsafeImageUrlKeepsTheArticleButDropsTheImage() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"ok","news":[
                  {"title":"t","url":"https://example.com/a","image":"javascript:alert(1)"}
                ]}
                """);
        List<NewsArticle> articles = client(http, "key").search("q", "en", 3);
        assertThat(articles).hasSize(1);
        assertThat(articles.get(0).imageUrl()).isNull();
    }

    @Test
    void aNonOkStatusIsNotReadAsEmpty() {
        CannedHttp http = new CannedHttp().status(400, """
                {"status":"400","msg":"Bad request"}
                """);
        assertThat(client(http, "key").search("q", "en", 3)).isEmpty();
        // A real failure, not a coincidental empty list — three in a row opens the breaker.
        assertThat(client(new CannedHttp().status(400, "{}"), "key").search("q", "en", 3)).isEmpty();
        assertThat(client(new CannedHttp().status(400, "{}"), "key").search("q", "en", 3)).isEmpty();
        assertThat(breaker.paused(CurrentsClient.SERVICE)).isTrue();
    }

    /** A 200 whose own status field says failure (a bad key, a rate limit) is a failure, not zero results. */
    @Test
    void aBodyLevelNonOkStatusIsNotReadAsEmpty() {
        CannedHttp http = new CannedHttp().ok("""
                {"status":"error","message":"invalid apiKey"}
                """);
        assertThat(client(http, "bad").search("q", "en", 3)).isEmpty();
        assertThat(client(new CannedHttp().ok("{\"status\":\"error\"}"), "bad").search("q", "en", 3)).isEmpty();
        assertThat(client(new CannedHttp().ok("{\"status\":\"error\"}"), "bad").search("q", "en", 3)).isEmpty();
        assertThat(breaker.paused(CurrentsClient.SERVICE)).isTrue();
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

    /** Runs the call with the class's log captured; returns every formatted message plus throwable text. */
    private String logged(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger(CurrentsClient.class);
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
        CurrentsClient c = new CurrentsClient(
                RestClient.builder().baseUrl("https://api.currentsapi.services").requestFactory(failing).build(),
                new NewsProperties(true, 0.9, 3, null,
                        new NewsProperties.Service("https://api.currentsapi.services", "secret123", 250)),
                new ApiUsageService(usage), breaker);

        String log = logged(() -> c.search("q", "en", 3));

        assertThat(log).isNotBlank().doesNotContain("secret123").doesNotContain("apiKey=secret");
    }
}
