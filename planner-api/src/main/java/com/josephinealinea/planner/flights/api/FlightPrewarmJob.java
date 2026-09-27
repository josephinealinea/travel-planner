package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Warms the cache once a night for flights leaving within 72 hours, so the first
 * reader to press refresh does not wait on a call.
 *
 * <b>Never the only path.</b> On free-tier Cloud Run the CPU exists only while a
 * request runs, so this cron may never fire; refresh-on-read in
 * {@link FlightData} is the real mechanism and this only means fewer first
 * clicks wait. A failed run leaves nothing worse than a cold cache.
 *
 * Published trips only (nobody sees flight status otherwise), deduped by
 * operating number and date, soonest first, stopping at the AeroDataBox cap or
 * when AeroDataBox's circuit breaker opens.
 * AviationStack is never called here: it is click-only, inside its window.
 */
@Component
public class FlightPrewarmJob {

    private static final Logger log = LoggerFactory.getLogger(FlightPrewarmJob.class);
    private static final Duration HORIZON = Duration.ofHours(72);

    private record Candidate(String number, LocalDate date, Instant departure) {}

    private final TripRepository trips;
    private final ItineraryRepository itinerary;
    private final CodeshareRepository codeshares;
    private final FlightData data;
    private final ApiUsageService usage;
    private final FlightProperties props;
    private final CircuitBreaker breaker;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightPrewarmJob(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                            FlightData data, ApiUsageService usage, FlightProperties props, CircuitBreaker breaker) {
        this(trips, itinerary, codeshares, data, usage, props, breaker, Clock.systemUTC());
    }

    FlightPrewarmJob(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                     FlightData data, ApiUsageService usage, FlightProperties props, CircuitBreaker breaker,
                     Clock clock) {
        this.trips = trips;
        this.itinerary = itinerary;
        this.codeshares = codeshares;
        this.data = data;
        this.usage = usage;
        this.props = props;
        this.breaker = breaker;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.flights.prewarm.cron:0 0 1 * * *}", zone = "UTC")
    public void run() {
        log.info("Warming flight cache");
        prewarm(clock.instant());
    }

    /**
     * Returns how many flights were actually warmed: a call was made and the
     * service answered (found, or a genuine "no such flight"). Flights already
     * fresh and calls that failed are logged separately and not counted.
     * Stops as soon as AeroDataBox's circuit breaker is open (after
     * {@code app.circuit-breaker.failure-threshold} consecutive failed calls, or
     * already open from readers' clicks), so an outage cannot burn the monthly
     * budget the click path needs.
     */
    public int prewarm(Instant now) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Trip trip : trips.findAllPublished()) {
            for (ItineraryItem entry : itinerary.findAll(trip.getSlug())) {
                try {
                    Candidate candidate = candidateOf(entry, now);
                    if (candidate != null) unique.putIfAbsent(candidate.number() + "|" + candidate.date(), candidate);
                } catch (Exception e) {
                    log.warn("Skipping a malformed entry on trip {} ({}: {})", trip.getSlug(),
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
        List<Candidate> soonestFirst = unique.values().stream()
                .sorted(Comparator.comparing(Candidate::departure)).toList();

        int warmed = 0, fresh = 0, unavailable = 0, skipped = 0;
        for (Candidate candidate : soonestFirst) {
            // paused, not isOpen: a peek must not claim the breaker's single trial call
            if (breaker.paused(AeroDataBoxClient.SERVICE)) {
                log.warn("AeroDataBox is paused by the circuit breaker; stopping the prewarm");
                break;
            }
            if (usage.calls(AeroDataBoxClient.SERVICE) >= props.aerodatabox().cap()) {
                log.info("AeroDataBox cap reached; stopping the prewarm");
                break;
            }
            int before = usage.calls(AeroDataBoxClient.SERVICE);
            try {
                FlightData.Result result = data.schedule(candidate.number(), candidate.date(), candidate.departure());
                boolean called = usage.calls(AeroDataBoxClient.SERVICE) > before;
                if (!called) {
                    // No call: either the record is fresh, or the breaker skipped it (e.g. a trial already out).
                    if (breaker.paused(AeroDataBoxClient.SERVICE)) skipped++;
                    else fresh++;
                } else if (result.status() == Fetch.Status.UNAVAILABLE || result.stale()) {
                    unavailable++;
                } else {
                    warmed++;
                }
            } catch (Exception e) {
                log.warn("Prewarm of {} on {} failed: {}", candidate.number(), candidate.date(), e.getMessage());
                unavailable++;
            }
        }
        log.info("Flight prewarm: {} warmed, {} already fresh, {} unavailable, {} skipped by the circuit breaker",
                warmed, fresh, unavailable, skipped);
        return warmed;
    }

    private Candidate candidateOf(ItineraryItem entry, Instant now) {
        FlightSnapshot flight = entry.getFlight();
        if (flight == null || flight.number() == null || entry.getStartAt() == null) return null;
        String zone = flight.from() == null ? null : flight.from().timezone();
        Instant departure = entry.getStartAt().atZone(zoneOf(zone)).toInstant();
        if (departure.isBefore(now) || departure.isAfter(now.plus(HORIZON))) return null;
        String number = flight.operatingNumber() != null
                ? flight.operatingNumber()
                : codeshares.operatingFor(flight.number()).orElse(flight.number());
        return new Candidate(number, entry.getStartAt().toLocalDate(), departure);
    }

    /** A missing or unreadable (hand-edited) timezone falls back to UTC rather than aborting the run. */
    private static ZoneId zoneOf(String zone) {
        try {
            return zone == null ? ZoneId.of("UTC") : ZoneId.of(zone);
        } catch (DateTimeException badZone) {
            return ZoneId.of("UTC");
        }
    }
}
