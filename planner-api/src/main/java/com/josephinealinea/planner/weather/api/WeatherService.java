package com.josephinealinea.planner.weather.api;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.weather.DayWeather;
import com.josephinealinea.planner.weather.WeatherClient;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the trip is on each of its days, with that day's weather.
 *
 * This is the one thing the itinerary knows that is not an itinerary entry. A
 * destination already says where you are and for how long, so every day it
 * covers is a day the trip has a location — and a location plus a date is
 * exactly what a weather lookup needs. The rows themselves are computed from
 * the destinations on every request, the same way nights and days are; only the
 * readings are stored.
 *
 * Both ends of a destination's range count, so Cusco 25-Oct to 31-Oct produces
 * seven rows and a day trip produces one — the same reading as the Days column
 * in the Destinations table. Two destinations on the same date produce two rows
 * for that date, which is correct: on a travel day you really are in both.
 *
 * A destination needs dates to appear at all — with none there is no day to put
 * it on. It does not need coordinates: the row still shows where you are, with
 * the weather marked unavailable, because some real places have no gazetteer
 * entry and free text always saves (see the countries.dev notes in CLAUDE.md).
 *
 * <h2>When Open-Meteo is actually called</h2>
 *
 * This class owns the cache, because a cache has to be scoped to a trip to be
 * stored and deleted with one. Three rules decide whether a day needs asking
 * about, and the first is the one that matters most:
 *
 * <ol>
 *   <li><b>A past date with a record is never looked up again.</b> The weather
 *       on 25 October stops changing once that day is over, so the record is
 *       final. What is kept is whatever was last held — a forecast, or a
 *       climate projection — still carrying its own {@code source}, so a
 *       preserved guess never poses as a measurement.</li>
 *   <li>A past date with <i>no</i> record is fetched once, then falls under
 *       rule 1 forever. Otherwise a trip entered after the fact could never
 *       show weather at all, and "no record yet" is exactly the case a lookup
 *       is for.</li>
 *   <li>A present or future date is refetched once its record passes
 *       {@code app.weather.cache-ttl}, since a forecast for a day still to come
 *       genuinely changes.</li>
 * </ol>
 *
 * Only the points with a gap are asked about, so adding one destination costs a
 * lookup for that destination rather than a refetch of the other four. A trip
 * whose days are all settled costs no outbound call at all — which, once its
 * dates are in the past, is permanent.
 */
@Service
public class WeatherService {

    private final DestinationRepository destinations;
    private final WeatherRepository stored;
    private final TripAccessService access;
    private final WeatherClient weather;
    private final Duration ttl;
    private final Clock clock;

    // @Autowired because there are two constructors. Without it Spring cannot
    // choose, falls back to looking for a no-arg one, and the app fails to
    // start — see CLAUDE.md, Traps.
    @Autowired
    public WeatherService(DestinationRepository destinations,
                          WeatherRepository stored,
                          TripAccessService access,
                          WeatherClient weather,
                          AppProperties props) {
        this(destinations, stored, access, weather, props, Clock.systemUTC());
    }

    /**
     * Clock injected so a test can pin which dates count as past — the whole
     * past-date rule turns on that comparison, so it has to be controllable.
     * Public because the test lives in the sibling package.
     */
    public WeatherService(DestinationRepository destinations,
                   WeatherRepository stored,
                   TripAccessService access,
                   WeatherClient weather,
                   AppProperties props,
                   Clock clock) {
        this.destinations = destinations;
        this.stored = stored;
        this.access = access;
        this.weather = weather;
        this.ttl = props.weather().cacheTtl();
        this.clock = clock;
    }

    public List<DayWeather> forTrip(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        String slug = trip.getSlug();

        List<Destination> dated = destinations.findAllOrdered(slug).stream()
                .filter(WeatherService::hasUsableDates)
                .toList();
        if (dated.isEmpty()) return List.of();

        Map<String, WeatherRecord> held = new LinkedHashMap<>();
        stored.findAll(slug).forEach(record -> held.put(record.getId(), record));

        Map<String, WeatherRecord> resolved = fill(slug, dated, held);

        List<DayWeather> rows = new ArrayList<>();
        for (Destination destination : dated) {
            rows.addAll(daysOf(destination, resolved));
        }
        // By date, and within a date by the order the destinations are listed,
        // which is the order the trip visits them.
        rows.sort((a, b) -> a.date().compareTo(b.date()));
        return rows;
    }

    /**
     * Works out which days still need asking about, asks in one batch, and
     * writes what comes back.
     *
     * Returns every record now available for this trip, whether it was already
     * held or has just arrived — including the expired ones, which is what lets
     * a failed lookup fall back to a stale reading rather than to nothing. By
     * that point the lookup has already failed, and half-day-old numbers still
     * tell somebody what to pack.
     */
    private Map<String, WeatherRecord> fill(String slug,
                                            List<Destination> dated,
                                            Map<String, WeatherRecord> held) {
        LocalDate today = LocalDate.now(clock);
        Instant now = clock.instant();

        List<WeatherClient.Point> needed = new ArrayList<>();
        LocalDate neededFrom = null;
        LocalDate neededTo = null;

        for (Destination destination : dated) {
            if (!destination.hasCoordinates()) continue;
            boolean anyGap = false;

            for (LocalDate day = destination.getStartDate();
                 !day.isAfter(destination.getEndDate());
                 day = day.plusDays(1)) {
                WeatherRecord record = held.get(
                        WeatherRecord.keyFor(destination.getLatitude(), destination.getLongitude(), day));

                // Rule 1: a past date already answered is final.
                if (record != null && record.isForDateInThePast(today)) continue;
                // Rule 3: a future date is good until the TTL lapses.
                if (record != null && !record.olderThan(now, ttl)) continue;

                anyGap = true;
                if (neededFrom == null || day.isBefore(neededFrom)) neededFrom = day;
                if (neededTo == null || day.isAfter(neededTo)) neededTo = day;
            }

            if (anyGap) {
                needed.add(new WeatherClient.Point(destination.getId(),
                        destination.getLatitude(), destination.getLongitude()));
            }
        }

        if (needed.isEmpty()) return held;

        Map<String, Map<LocalDate, WeatherClient.Reading>> fetched =
                weather.lookUp(needed, neededFrom, neededTo);

        List<WeatherRecord> toSave = new ArrayList<>();
        for (WeatherClient.Point point : needed) {
            fetched.getOrDefault(point.id(), Map.of()).forEach((day, reading) -> {
                WeatherRecord record = new WeatherRecord();
                record.setId(WeatherRecord.keyFor(point.latitude(), point.longitude(), day));
                record.setLatitude(point.latitude());
                record.setLongitude(point.longitude());
                record.setDate(day);
                record.setWeatherCode(reading.weatherCode());
                record.setTemperatureMax(reading.temperatureMax());
                record.setTemperatureMin(reading.temperatureMin());
                record.setPrecipitation(reading.precipitation());
                record.setSource(reading.source());
                record.setFetchedAt(now);
                toSave.add(record);
                held.put(record.getId(), record);
            });
        }

        // One write for the batch, or none at all when the lookup failed —
        // remembering an empty answer would blank the itinerary for the whole
        // TTL over a blip that had already passed.
        if (!toSave.isEmpty()) stored.saveAll(slug, toSave);
        return held;
    }

    private static boolean hasUsableDates(Destination destination) {
        LocalDate from = destination.getStartDate();
        LocalDate to = destination.getEndDate();
        return from != null && to != null && !to.isBefore(from);
    }

    private List<DayWeather> daysOf(Destination destination,
                                    Map<String, WeatherRecord> resolved) {
        List<DayWeather> days = new ArrayList<>();
        for (LocalDate day = destination.getStartDate();
             !day.isAfter(destination.getEndDate());
             day = day.plusDays(1)) {

            WeatherRecord record = destination.hasCoordinates()
                    ? resolved.get(WeatherRecord.keyFor(
                            destination.getLatitude(), destination.getLongitude(), day))
                    : null;

            if (record == null) {
                days.add(DayWeather.unavailable(day, destination.getId(), destination.getName(),
                        destination.getCountryCode(), destination.getCountryFlag()));
                continue;
            }
            days.add(new DayWeather(day,
                    destination.getId(),
                    destination.getName(),
                    destination.getCountryCode(),
                    destination.getCountryFlag(),
                    record.getWeatherCode(),
                    record.getTemperatureMax(),
                    record.getTemperatureMin(),
                    record.getPrecipitation(),
                    record.getSource()));
        }
        return days;
    }
}
