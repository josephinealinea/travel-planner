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
            // Never the raw message: a RestClient I/O error quotes the whole URI, key included.
            log.warn("NewsData search for {} failed: {}", query, e.getClass().getSimpleName());
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
                String url = SafeUrl.httpOnly(text(item, "link"));
                if (url == null) continue; // no safe link to show at all
                articles.add(new NewsArticle(
                        text(item, "title"), text(item, "description"), url,
                        SafeUrl.httpOnly(text(item, "image_url")), text(item, "source_name"),
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
