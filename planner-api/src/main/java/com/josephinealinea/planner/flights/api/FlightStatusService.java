package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightNumbers;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * What a reader gets when they press refresh on a published flight.
 *
 * <b>Never an open proxy.</b> The flight must be on the entries of a
 * <i>published</i> trip, matched by number (booked or operating) and local
 * start date; anything else is a 404. It returns flight status only, never the
 * entry.
 *
 * <b>Which calls.</b> Refresh on read: AeroDataBox when the record is missing or
 * past its tier TTL, and only while the entry departs within
 * {@code app.flights.public-refresh.window-before} before now to
 * {@code window-after} after it; outside that a reader gets what is held, or
 * nothing. AviationStack is added only from the window before departure until
 * landing, when its own 15 minutes has lapsed, at most
 * {@code max-live-attempts-per-flight} times per flight and date, and never to
 * resolve a codeshare (that is the entry form's job), so a reader's click cannot
 * spend the scarce quota on resolution.
 *
 * <b>Failures back off per flight</b> on the circuit breaker's ladder
 * ({@code app.circuit-breaker.back-off}), and a whole-service outage is the
 * clients' service-wide breaker's to stop.
 */
@Service
public class FlightStatusService {

    public record View(String number, String operatingNumber, FlightSchedule schedule, FlightLive live,
                       Instant scheduleFetchedAt, Instant liveFetchedAt, boolean stale, long ttlSeconds) {}

    /** How long a live-attempt count is kept once its flight has gone quiet. */
    private static final Duration LIVE_ATTEMPTS_KEPT = Duration.ofDays(1);
    private static final int MAX_TRACKED = 1000;

    /** AviationStack attempts for one key, and when the last one was. */
    private record Attempts(int count, Instant last) {}

    // In memory: YAML mode and Cloud Run are single-instance.
    private final Map<String, Attempts> stackAttempts = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final TripRepository trips;
    private final ItineraryRepository itinerary;
    private final CodeshareRepository codeshares;
    private final FlightData data;
    private final AviationStackClient stack;
    private final FlightRecordRepository records;
    private final FlightFreshness rules;
    private final FlightProperties.PublicRefresh limits;
    private final CircuitBreaker breaker;
    private final ApiUsageService usage;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightStatusService(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                               FlightData data, AviationStackClient stack, FlightRecordRepository records,
                               FlightFreshness rules, FlightProperties props, CircuitBreaker breaker,
                               ApiUsageService usage) {
        this(trips, itinerary, codeshares, data, stack, records, rules, props, breaker, usage, Clock.systemUTC());
    }

    FlightStatusService(TripRepository trips, ItineraryRepository itinerary, CodeshareRepository codeshares,
                        FlightData data, AviationStackClient stack, FlightRecordRepository records,
                        FlightFreshness rules, FlightProperties props, CircuitBreaker breaker,
                        ApiUsageService usage, Clock clock) {
        this.trips = trips;
        this.itinerary = itinerary;
        this.codeshares = codeshares;
        this.data = data;
        this.stack = stack;
        this.records = records;
        this.rules = rules;
        this.limits = props.publicRefresh();
        this.breaker = breaker;
        this.usage = usage;
        this.clock = clock;
    }

    /** Empty when there is nothing to show yet (the page keeps what it published). */
    public Optional<View> status(String slug, String rawNumber, LocalDate date) {
        Trip trip = trips.findBySlug(Slugs.requireSafe(slug))
                .filter(Trip::isPublished)
                .orElseThrow(() -> ApiException.notFound("error.flight.notFound"));
        String number = FlightNumbers.normalise(rawNumber);
        ItineraryItem entry = itinerary.findAll(trip.getSlug()).stream()
                .filter(e -> onDate(e, date) && hasNumber(e.getFlight(), number))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("error.flight.notFound"));

        FlightSnapshot flight = entry.getFlight();
        String booked = flight.number();
        String operating = flight.operatingNumber() != null
                ? flight.operatingNumber()
                : codeshares.operatingFor(booked).orElse(booked);

        // Only keys of real entries on a published trip get this far (the match
        // above comes first), so an anonymous caller cannot grow the maps below
        // with keys of their own choosing.
        String key = operating + "|" + date;
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        if (!lock.tryLock()) return held(flight, operating, date); // someone is already asking
        try {
            Instant hint = hintOf(entry);
            if (!withinPublicWindow(hint)) return heldOnly(flight, operating, date);
            return refresh(flight, operating, date, key, hint);
        } finally {
            lock.unlock();
            trimLocks();
        }
    }

    private Optional<View> refresh(FlightSnapshot flight, String operating, LocalDate date, String key, Instant hint) {
        Instant now = clock.instant();
        // Per flight: 1st failure waits back-off[0], 2nd back-off[1], then the last rung.
        if (breaker.isOpen(AeroDataBoxClient.SERVICE, key)) return held(flight, operating, date);

        // A flight is backed off only for a call it actually made. A paused
        // service, no key or a reached cap make none, and must not leave this
        // flight held for hours after the service recovers. Under the per-key
        // lock, so the counter moving means this refresh's call went out.
        boolean pausedBefore = breaker.paused(AeroDataBoxClient.SERVICE);
        int callsBefore = usage.calls(AeroDataBoxClient.SERVICE);
        FlightData.Result result = data.schedule(operating, date, hint);
        boolean called = !pausedBefore && usage.calls(AeroDataBoxClient.SERVICE) > callsBefore;
        if (result.status() == Fetch.Status.UNAVAILABLE || result.stale()) {
            if (called) breaker.failure(AeroDataBoxClient.SERVICE, key);
        } else if (!result.fromCache()) {
            breaker.success(AeroDataBoxClient.SERVICE, key); // a real answer: start counting again
        }
        if (result.record() == null) return Optional.empty();

        FlightRecord record = result.record();
        String booked = flight.number();
        // A number that flies two legs is cached as one record; the entry says which is its own.
        FlightSchedule leg = record.narrowedTo(departureIata(flight)).schedule();
        Instant departure = FlightFreshness.departureOf(leg);
        boolean over = rules.landed(leg, now);
        boolean live = !over && rules.inLiveWindow(now, departure);
        if (live && !rules.liveFresh(record, now)) {
            // Every ATTEMPT counts, not only successes: a miss or an outage stores
            // nothing, so without this each click would spend another call. Keyed
            // like the record and the lock, by operating flight, so two codeshare
            // numbers for one flight share one guard; capped per flight and date,
            // after which readers get the live extras already held.
            Attempts tried = stackAttempts.get(key);
            boolean due = tried == null || Duration.between(tried.last(), now).compareTo(rules.liveTtl()) >= 0;
            // A paused AviationStack makes no call, so it must not use up an attempt.
            if (due && (tried == null || tried.count() < limits.maxLiveAttemptsPerFlight())
                    && !breaker.paused(AviationStackClient.SERVICE)) {
                stackAttempts.put(key, new Attempts(tried == null ? 1 : tried.count() + 1, now));
                if (stackAttempts.size() > MAX_TRACKED) {
                    stackAttempts.values().removeIf(a -> Duration.between(a.last(), now).compareTo(LIVE_ATTEMPTS_KEPT) >= 0);
                }
                Fetch<FlightLive> extras = stack.live(booked, date);
                if (extras.status() == Fetch.Status.FOUND) {
                    record = record.withLive(extras.value(), now);
                    records.save(record);
                }
            }
        }

        Duration ttl = result.stale() ? limits.staleBrowserTtl() : Duration.ofSeconds(rules.browserTtlSeconds(record.narrowedTo(departureIata(flight)), now));
        return Optional.of(view(flight, operating, record, result.stale(), ttl));
    }

    /** Outside the public window: what is held, fresh or stale by its tier TTL, with no call at all. */
    private Optional<View> heldOnly(FlightSnapshot flight, String operating, LocalDate date) {
        Instant now = clock.instant();
        return records.find(operating, date)
                .filter(r -> !r.notFound() && r.schedule() != null)
                .map(r -> rules.scheduleFresh(r, now, null)
                        ? view(flight, operating, r, false, Duration.ofSeconds(rules.browserTtlSeconds(r.narrowedTo(departureIata(flight)), now)))
                        : view(flight, operating, r, true, limits.staleBrowserTtl()));
    }

    private boolean withinPublicWindow(Instant departure) {
        if (departure == null) return false;
        Instant now = clock.instant();
        return !departure.isBefore(now.minus(limits.windowBefore()))
                && !departure.isAfter(now.plus(limits.windowAfter()));
    }

    /** What is held, with no call at all; stale, so the page asks again soon. */
    private Optional<View> held(FlightSnapshot flight, String operating, LocalDate date) {
        return records.find(operating, date)
                .filter(r -> !r.notFound() && r.schedule() != null)
                .map(r -> view(flight, operating, r, true, limits.staleBrowserTtl()));
    }

    private static View view(FlightSnapshot flight, String operating, FlightRecord record, boolean stale, Duration ttl) {
        String operatingShown = flight.operatingNumber() != null ? flight.operatingNumber()
                : (operating.equals(flight.number()) ? null : operating);
        return new View(flight.number(), operatingShown, record.narrowedTo(departureIata(flight)).schedule(), record.live(),
                record.scheduleFetchedAt(), record.liveFetchedAt(), stale, ttl.toSeconds());
    }

    private static String departureIata(FlightSnapshot flight) {
        return flight.from() == null ? null : flight.from().iata();
    }

    private void trimLocks() {
        if (locks.size() > MAX_TRACKED) locks.values().removeIf(l -> !l.isLocked() && !l.hasQueuedThreads());
    }

    private static boolean onDate(ItineraryItem e, LocalDate date) {
        return e.getStartAt() != null && e.getStartAt().toLocalDate().equals(date);
    }

    private static boolean hasNumber(FlightSnapshot flight, String number) {
        return flight != null && number != null
                && (number.equals(flight.number()) || number.equals(flight.operatingNumber()));
    }

    /** When the entry says it departs, in the origin's timezone when the snapshot knows it. */
    private static Instant hintOf(ItineraryItem entry) {
        if (entry.getStartAt() == null) return null;
        String zone = entry.getFlight().from() == null ? null : entry.getFlight().from().timezone();
        try {
            return entry.getStartAt().atZone(zone == null ? ZoneId.of("UTC") : ZoneId.of(zone)).toInstant();
        } catch (DateTimeException badZone) {
            return entry.getStartAt().atZone(ZoneId.of("UTC")).toInstant();
        }
    }
}
