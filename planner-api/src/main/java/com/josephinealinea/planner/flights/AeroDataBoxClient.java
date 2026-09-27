package com.josephinealinea.planner.flights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final CircuitBreaker breaker;

    // The parameter name is the bean name Spring injects by: see FlightsConfig.
    public AeroDataBoxClient(RestClient aeroDataBoxHttp, FlightProperties props, ApiUsageService usage,
                             CircuitBreaker breaker) {
        this.http = aeroDataBoxHttp;
        this.config = props.aerodatabox();
        this.usage = usage;
        this.breaker = breaker;
    }

    /** The flight departing on {@code departureDate} (local date at the origin). */
    /** The first leg only; the legs of a multi-leg number are {@link #legs}. */
    public Fetch<FlightSchedule> flight(String number, LocalDate departureDate) {
        Fetch<List<FlightSchedule>> legs = legs(number, departureDate);
        return legs.status() == Fetch.Status.FOUND ? Fetch.found(legs.value().get(0)) : Fetch.of(legs.status());
    }

    /** Every leg this number flies that day, earliest departure first. */
    public Fetch<List<FlightSchedule>> legs(String number, LocalDate departureDate) {
        // In this order: a paused service must not spend quota, and none of these
        // three is an attempt, so none is reported to the breaker.
        if (!config.enabled()) return Fetch.unavailable();
        if (breaker.isOpen(SERVICE)) {
            log.debug("AeroDataBox is paused by the circuit breaker; not calling");
            return Fetch.unavailable();
        }
        if (!usage.tryAcquire(SERVICE, config.cap())) {
            log.info("AeroDataBox monthly cap ({}) reached; not calling", config.cap());
            return Fetch.unavailable();
        }
        Fetch<List<FlightSchedule>> result = ask(number, departureDate);
        // "No such flight" is an answer; only an attempt that got none is a failure.
        if (result.status() == Fetch.Status.UNAVAILABLE) breaker.failure(SERVICE);
        else breaker.success(SERVICE);
        return result;
    }

    private Fetch<List<FlightSchedule>> ask(String number, LocalDate departureDate) {
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

    private Fetch<List<FlightSchedule>> interpret(Raw raw, LocalDate date) throws Exception {
        if (raw.status() == 204 || raw.status() == 404) return Fetch.notFound();
        if (raw.status() != 200) {
            log.warn("AeroDataBox answered {}", raw.status());
            return Fetch.unavailable();
        }
        if (raw.body() == null || raw.body().isBlank()) return Fetch.notFound();

        JsonNode root = JSON.readTree(raw.body());
        if (!root.isArray()) {
            // Valid JSON that is not a flight list (error object, null, scalar) is not an empty answer.
            log.warn("AeroDataBox answered 200 with a non-array body");
            return Fetch.unavailable();
        }
        if (root.isEmpty()) return Fetch.notFound();
        boolean readable = false;
        List<FlightSchedule> legs = new ArrayList<>();
        for (JsonNode flight : root) {
            String local = text(flight.path("departure").path("scheduledTime"), "local");
            if (local == null) continue;
            readable = true;
            if (local.startsWith(date.toString())) legs.add(schedule(flight));
        }
        if (!readable) {
            log.warn("AeroDataBox answered 200 with entries that have no departure time");
            return Fetch.unavailable();
        }
        if (legs.isEmpty()) return Fetch.notFound();
        legs.sort(Comparator.comparing(l -> l.departure().scheduledLocal() == null ? "" : l.departure().scheduledLocal()));
        return Fetch.found(legs);
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
