package com.josephinealinea.planner.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Daily weather from Open-Meteo, which is free and needs no key.
 *
 * Two things about the live service shape this class, and neither is guessable
 * from the docs. Both were confirmed by watching it.
 *
 * <b>1. The forecast endpoint will not answer for most of the dates this app
 * asks about.</b> It serves a sliding window of roughly 92 days back to 16 days
 * ahead and answers HTTP 400 — {@code "Parameter 'start_date' is out of allowed
 * range from 2026-06-13 to 2026-09-29"} — for anything else, for the *whole
 * call*, not just the days outside it. A trip is normally booked months out, so
 * a range the forecast endpoint refuses outright is the common case, not an
 * edge case. Requests are therefore split at the forecast horizon:
 *
 * <ul>
 *   <li>inside the window → {@code /forecast}, a real prediction, and the only
 *       source that fills in {@code weather_code};</li>
 *   <li>outside it → {@code /climate}, CMIP6 downscaled to 10km, daily,
 *       1950-2050. It answers for any date a trip could carry, in either
 *       direction, which is why there is no third call for the distant past.
 *       It accepts {@code weather_code} and returns a column of nulls, so a
 *       condition is derived from precipitation instead.</li>
 * </ul>
 *
 * Which one answered rides along on every day as {@link DayWeather.Source},
 * because a climate projection is not a forecast and a reader packing a bag
 * needs to be told which they are looking at.
 *
 * <b>2. It rate-limits bursts, and a tripped limiter does not answer at all.</b>
 * The climate endpoint takes a couple of requests in quick succession and then
 * goes silent: TCP connects in 40ms, the request is accepted, and no response
 * ever arrives until the socket times out. It is worth being precise about
 * what this is *not*, because each looks plausible and each is wrong — it is
 * not IPv6 (both resolved addresses connect, and preferIPv4Stack changes
 * nothing), not TLS (a hand-written GET over an SSLSocket gets 200 in 60ms),
 * not the HTTP version, not the User-Agent, and not any particular query
 * parameter. The same URL alternates between 200, {@code 429 "Too many
 * concurrent requests"}, {@code 500 "Something went wrong."} and silence
 * purely on how recently the caller last asked.
 *
 * So a read timeout here almost always means throttling, not a slow model —
 * the endpoint answers in about 200ms when it answers. The thing actually
 * within this app's control is the number of calls, so: both endpoints accept
 * comma-separated coordinate lists and answer with a JSON array, one entry per
 * location in the order asked. A whole trip therefore costs **at most two**
 * outbound calls — one per date regime — however many destinations it has.
 *
 * Nothing is cached here. This class is a stateless fetcher on purpose: a
 * cache has to be scoped to a trip so it can be stored and deleted with one,
 * and this class deliberately knows nothing about trips. WeatherService owns
 * the cache, backed by WeatherRepository.
 */
@Service
public class WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(WeatherClient.class);

    private static final String DAILY_FORECAST =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum";
    private static final String DAILY_CLIMATE =
            "temperature_2m_max,temperature_2m_min,precipitation_sum";
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final RestClient forecast;
    private final RestClient climate;
    private final AppProperties.Weather config;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public WeatherClient(RestClient openMeteoForecastClient,
                         RestClient openMeteoClimateClient,
                         AppProperties props) {
        this(openMeteoForecastClient, openMeteoClimateClient, props, Clock.systemUTC());
    }

    /** Clock injected so a test can pin what "inside the forecast window" means. */
    WeatherClient(RestClient forecast, RestClient climate, AppProperties props, Clock clock) {
        this.forecast = forecast;
        this.climate = climate;
        this.config = props.weather();
        this.clock = clock;
    }

    /** One place to look up, tagged with whatever the caller wants it back under. */
    public record Point(String id, double latitude, double longitude) {}

    /** One day as Open-Meteo answered it, before it is dressed up for a view. */
    public record Reading(Integer weatherCode,
                          Double temperatureMax,
                          Double temperatureMin,
                          Double precipitation,
                          DayWeather.Source source) {}

    /**
     * Looks up every point for every date in the range. No caching here — the
     * caller owns that, because a cache has to be scoped to a trip and this
     * class deliberately knows nothing about trips.
     *
     * Split at the forecast horizon and batched: the points go into one
     * coordinate list per date regime, because Open-Meteo throttles bursts and
     * a call per point is what trips it. At most two outbound calls, however
     * many points are asked about.
     */
    public Map<String, Map<LocalDate, Reading>> lookUp(List<Point> points,
                                                       LocalDate from, LocalDate to) {
        if (points == null || points.isEmpty()) return Map.of();
        if (from == null || to == null || to.isBefore(from)) return Map.of();

        Map<String, Map<LocalDate, Reading>> answer = new LinkedHashMap<>();
        points.forEach(point -> answer.put(point.id(), new LinkedHashMap<>()));

        LocalDate today = LocalDate.now(clock);
        LocalDate windowStart = today.minusDays(config.historyDays());
        LocalDate windowEnd = today.plusDays(config.horizonDays());

        // The forecastable slice, if the range overlaps the window at all.
        LocalDate nearFrom = from.isBefore(windowStart) ? windowStart : from;
        LocalDate nearTo = to.isAfter(windowEnd) ? windowEnd : to;
        if (!nearFrom.isAfter(nearTo)) {
            absorb(answer, points, fetchForecast(points, nearFrom, nearTo));
        }

        // Everything outside it, from the climate model. One call spanning the
        // whole remainder rather than one per gap: a trip either sits entirely
        // outside the window or straddles one end of it, and a few extra days
        // of model output costs nothing next to a second request that might be
        // refused. Forecast days already collected are not overwritten — see
        // absorb.
        if (from.isBefore(windowStart) || to.isAfter(windowEnd)) {
            absorb(answer, points, fetchClimate(points, from, to));
        }
        return answer;
    }

    /**
     * Folds one call's answers into the result, by position.
     *
     * Order is the contract: Open-Meteo answers a coordinate list with an array
     * in the order it was asked. A day already present is left alone, so the
     * climate call — which spans the forecast window rather than carving
     * around it — never overwrites a real forecast with a projection.
     */
    private void absorb(Map<String, Map<LocalDate, Reading>> into,
                        List<Point> points,
                        List<Map<LocalDate, Reading>> answers) {
        for (int i = 0; i < points.size() && i < answers.size(); i++) {
            Map<LocalDate, Reading> target = into.get(points.get(i).id());
            if (target != null) answers.get(i).forEach(target::putIfAbsent);
        }
    }

    private List<Map<LocalDate, Reading>> fetchForecast(List<Point> points,
                                                        LocalDate from, LocalDate to) {
        JsonNode body = fetch(forecast, uri -> uri
                .path("/forecast")
                .queryParam("latitude", join(points, Point::latitude))
                .queryParam("longitude", join(points, Point::longitude))
                .queryParam("daily", DAILY_FORECAST)
                .queryParam("start_date", from)
                .queryParam("end_date", to)
                .queryParam("timezone", "auto")
                .build());
        return read(body, DayWeather.Source.FORECAST);
    }

    private List<Map<LocalDate, Reading>> fetchClimate(List<Point> points,
                                                       LocalDate from, LocalDate to) {
        JsonNode body = fetch(climate, uri -> uri
                .path("/climate")
                .queryParam("latitude", join(points, Point::latitude))
                .queryParam("longitude", join(points, Point::longitude))
                .queryParam("models", config.climateModel())
                .queryParam("daily", DAILY_CLIMATE)
                .queryParam("start_date", from)
                .queryParam("end_date", to)
                .queryParam("timezone", "auto")
                .build());
        return read(body, DayWeather.Source.CLIMATE);
    }

    private static String join(List<Point> points,
                               java.util.function.ToDoubleFunction<Point> value) {
        return points.stream()
                .map(point -> String.valueOf(value.applyAsDouble(point)))
                .collect(Collectors.joining(","));
    }

    /**
     * A weather lookup is never allowed to break the page it decorates. Every
     * failure comes back as no readings, and the itinerary shows the day with
     * no weather on it rather than an error.
     */
    private JsonNode fetch(RestClient client, UriFn uri) {
        Outcome first = attempt(client, uri);
        if (first.body() != null || !first.worthRetrying()) return first.body();

        // The same URL that 500s or hangs answers 200 a moment later, so one
        // retry turns most blips into a reading. Never after a 429: that is the
        // service saying it has had too many requests, and asking again is the
        // one response guaranteed to make it worse.
        try {
            Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return attempt(client, uri).body();
    }

    /** A single attempt, and whether trying once more could plausibly help. */
    private record Outcome(JsonNode body, boolean worthRetrying) {
        static final Outcome GIVE_UP = new Outcome(null, false);
        static final Outcome TRANSIENT = new Outcome(null, true);
    }

    /** Shorthand for the URI builder these calls all pass around. */
    private interface UriFn extends java.util.function.Function<
            org.springframework.web.util.UriBuilder, java.net.URI> {}

    private Outcome attempt(RestClient client, UriFn uri) {
        try {
            return client.get().uri(uri).exchange((request, response) -> {
                var status = response.getStatusCode();
                if (status.value() == 429) {
                    log.warn("Open-Meteo rate-limited this lookup (429): {}", describe(uri));
                    return Outcome.GIVE_UP;
                }
                if (status.is5xxServerError()) {
                    log.warn("Open-Meteo answered {} for {}", status, describe(uri));
                    return Outcome.TRANSIENT;
                }
                if (status.isError()) {
                    // A 4xx is about the request, so asking again with the same
                    // one cannot help. The body names the reason — for the
                    // forecast endpoint, the window it would have accepted,
                    // which is the only way to notice the horizon moved.
                    log.warn("Open-Meteo rejected {}: {} {}", describe(uri), status,
                            safeBody(response));
                    return Outcome.GIVE_UP;
                }
                JsonNode body = json.readTree(response.getBody());
                // A 200 can still carry {"error":true,"reason":...}.
                if (body != null && body.path("error").asBoolean(false)) {
                    log.warn("Open-Meteo declined a lookup: {}", body.path("reason").asText());
                    return Outcome.GIVE_UP;
                }
                return new Outcome(body, false);
            }, false);
        } catch (Exception e) {
            // A timeout here is the same overload wearing a different hat.
            log.warn("Open-Meteo lookup failed for {}: {}", describe(uri), e.getMessage());
            return Outcome.TRANSIENT;
        }
    }

    /**
     * One entry per location asked for, in that order.
     *
     * A single coordinate comes back as a bare object rather than a one-element
     * array, so both shapes are accepted — otherwise a one-destination trip
     * would be the only one that never showed weather.
     */
    private List<Map<LocalDate, Reading>> read(JsonNode body, DayWeather.Source source) {
        if (body == null) return List.of();

        List<Map<LocalDate, Reading>> all = new ArrayList<>();
        if (body.isArray()) {
            body.forEach(location -> all.add(readOne(location, source)));
        } else {
            all.add(readOne(body, source));
        }
        return all;
    }

    private Map<LocalDate, Reading> readOne(JsonNode location, DayWeather.Source source) {
        JsonNode daily = location.path("daily");
        JsonNode times = daily.path("time");
        if (!times.isArray()) return Map.of();

        Map<LocalDate, Reading> readings = new LinkedHashMap<>();
        for (int i = 0; i < times.size(); i++) {
            Double max = number(daily, "temperature_2m_max", i);
            Double min = number(daily, "temperature_2m_min", i);
            // A row with no temperature at all says nothing; skip it rather
            // than render an empty badge.
            if (max == null && min == null) continue;

            Double rain = number(daily, "precipitation_sum", i);
            Double code = number(daily, "weather_code", i);
            readings.put(LocalDate.parse(times.get(i).asText()),
                    new Reading(code == null ? null : code.intValue(), max, min, rain, source));
        }
        return readings;
    }

    /** Null for a missing column, a short column, or an explicit JSON null. */
    private static Double number(JsonNode daily, String field, int index) {
        JsonNode column = daily.path(field);
        if (!column.isArray() || index >= column.size()) return null;
        JsonNode value = column.get(index);
        return value == null || value.isNull() ? null : value.asDouble();
    }

    private static String safeBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            return new String(response.getBody().readAllBytes()).trim();
        } catch (Exception e) {
            return "(body unavailable)";
        }
    }

    /** The URI a failed call asked for, so a remote failure is diagnosable. */
    private static String describe(UriFn uri) {
        try {
            return uri.apply(org.springframework.web.util.UriComponentsBuilder.newInstance())
                    .toString();
        } catch (Exception e) {
            return "(uri unavailable)";
        }
    }

}
