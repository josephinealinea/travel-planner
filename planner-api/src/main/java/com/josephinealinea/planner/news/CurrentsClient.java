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
            // Never the raw message: a RestClient I/O error quotes the whole URI, key included.
            log.warn("Currents search for {} failed: {}", query, e.getClass().getSimpleName());
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
                String url = SafeUrl.httpOnly(text(item, "url"));
                if (url == null) continue; // no safe link to show at all
                articles.add(new NewsArticle(
                        text(item, "title"), text(item, "description"), url,
                        SafeUrl.httpOnly(text(item, "image")), text(item, "author"),
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
