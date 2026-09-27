package com.josephinealinea.planner.flights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.resilience.CircuitBreaker;
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
    private final CircuitBreaker breaker;

    public AviationStackClient(RestClient aviationStackHttp, FlightProperties props, ApiUsageService usage,
                             CircuitBreaker breaker) {
        this.http = aviationStackHttp;
        this.config = props.aviationstack();
        this.usage = usage;
        this.breaker = breaker;
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
        // In this order: a paused service must not spend quota, and none of these
        // three is an attempt, so none is reported to the breaker.
        if (!config.enabled()) return Fetch.unavailable();
        if (breaker.isOpen(SERVICE)) {
            log.debug("AviationStack is paused by the circuit breaker; not calling");
            return Fetch.unavailable();
        }
        if (!usage.tryAcquire(SERVICE, config.cap())) {
            log.info("AviationStack monthly cap ({}) reached; not calling", config.cap());
            return Fetch.unavailable();
        }
        Fetch<JsonNode> result = ask(number);
        // An empty data array is an answer; only an attempt that got none is a failure.
        if (result.status() == Fetch.Status.UNAVAILABLE) breaker.failure(SERVICE);
        else breaker.success(SERVICE);
        return result;
    }

    private Fetch<JsonNode> ask(String number) {
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
                log.warn("AviationStack error: {}", redact(text(root.path("error"), "code")));
                return Fetch.unavailable();
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                // Missing or malformed data is not "no such flight": never cache it as a miss.
                log.warn("AviationStack answered 200 without a data array");
                return Fetch.unavailable();
            }
            return data.isEmpty() ? Fetch.notFound() : Fetch.found(data);
        } catch (Exception e) {
            // Never the raw message: a RestClient I/O error quotes the whole URI, key included.
            log.warn("AviationStack lookup of {} failed: {}", FlightNumbers.normalise(number),
                    e.getClass().getSimpleName());
            return Fetch.unavailable();
        }
    }

    /** Removes the key from text that might quote a URI or echo it. */
    private String redact(String text) {
        if (text == null) return null;
        String out = text.replaceAll("access_key=[^&\"\\s]*", "access_key=***");
        return config.key() == null || config.key().isBlank() ? out : out.replace(config.key(), "***");
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
