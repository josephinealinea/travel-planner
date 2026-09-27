package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.josephinealinea.planner.flights.api.FlightFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightStatusServiceTest {

    private static final String LIVE = """
            {"data":[{"flight_date":"2026-10-24","flight_status":"scheduled",
              "departure":{"iata":"TLL","gate":"8","delay":12},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"15","delay":0},
              "flight":{"number":"857","iata":"BT857","codeshared":null}}]}
            """;

    private FlightProperties props = new FlightProperties(
            new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
            new FlightProperties.Service("http://api.aviationstack.com/v1", "key", 100, 90, null),
            null, null, null, null);
    private final Records records = new Records();
    private final Codeshares codeshares = new Codeshares();
    private final Usage aeroUsage = new Usage();
    private final Usage stackUsage = new Usage();
    private final TripRepository trips = Mockito.mock(TripRepository.class);
    private final ItineraryRepository itinerary = Mockito.mock(ItineraryRepository.class);

    private void trip(TripStatus status, ItineraryItem... entries) {
        Trip trip = new Trip();
        trip.setId("t1");
        trip.setSlug("trip");
        trip.setStatus(status);
        Mockito.when(trips.findBySlug("trip")).thenReturn(Optional.of(trip));
        Mockito.when(itinerary.findAll("trip")).thenReturn(List.of(entries));
    }

    private static ItineraryItem entry(String booked, String operating, LocalDateTime start) {
        ItineraryItem item = new ItineraryItem();
        item.setStartAt(start);
        item.setFlight(new FlightSnapshot(booked, operating, null, null, null, null));
        return item;
    }

    private static final LocalDateTime DEPARTS = LocalDateTime.of(2026, 10, 24, 7, 30);

    private FlightStatusService service(CannedHttp aero, CannedHttp stack, Clock clock) {
        return service(aero, stack, clock, CircuitBreakerProperties.defaults());
    }

    /** One breaker, on the test's clock, shared by both clients and the service, as the one bean is. */
    private FlightStatusService service(CannedHttp aero, CannedHttp stack, Clock clock, CircuitBreakerProperties settings) {
        FlightFreshness rules = new FlightFreshness(props);
        CircuitBreaker breaker = new CircuitBreaker(settings, clock);
        FlightData data = new FlightData(
                new AeroDataBoxClient(aero.client(), props, new ApiUsageService(aeroUsage), breaker), records, rules, clock);
        return new FlightStatusService(trips, itinerary, codeshares, data,
                new AviationStackClient(stack.client(), props, new ApiUsageService(stackUsage), breaker),
                records, rules, props, breaker, new ApiUsageService(aeroUsage), clock);
    }

    @Test
    void aFlightOnAPublishedTripReturnsItsScheduleAndTheFetchTime() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));

        var view = service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();

        assertThat(view.number()).isEqualTo("BT857");
        assertThat(view.operatingNumber()).isNull();
        assertThat(view.schedule().departure().airport().iata()).isEqualTo("TLL");
        assertThat(view.scheduleFetchedAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(view.live()).isNull();
        assertThat(view.stale()).isFalse();
    }

    @Test
    void aFlightOnADraftTripIsNotFound() {
        trip(TripStatus.DRAFT, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp();

        assertThatThrownBy(() -> service(aero, new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "BT857", DAY))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.messageKey()).isEqualTo("error.flight.notFound");
                    assertThat(e.status().value()).isEqualTo(404);
                });
        assertThat(aero.callCount()).isZero();
    }

    @Test
    void aFlightNotOnThatTripIsNotFound() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp();

        assertThatThrownBy(() -> service(aero, new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "LH100", DAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.messageKey()).isEqualTo("error.flight.notFound"));
        assertThat(aero.callCount()).isZero();
    }

    /** Review focus 5. */
    @Test
    void anEntryWhoseDateWasEditedIsNotFoundForTheOldDate() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS.plusDays(1)));
        CannedHttp aero = new CannedHttp();

        assertThatThrownBy(() -> service(aero, new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "BT857", DAY))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.messageKey()).isEqualTo("error.flight.notFound"));
        assertThat(aero.callCount()).isZero();
    }

    @Test
    void theBookedOrTheOperatingNumberBothFindTheEntry() {
        trip(TripStatus.PUBLISHED, entry("KL2842", "BT857", DEPARTS));

        var byBooked = service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z"))
                .status("trip", "kl 2842", DAY).orElseThrow();
        var byOperating = service(new CannedHttp(), new CannedHttp(), at("2026-10-20T10:30:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();

        assertThat(byBooked.number()).isEqualTo("KL2842");
        assertThat(byBooked.operatingNumber()).isEqualTo("BT857");
        assertThat(byOperating.number()).isEqualTo("KL2842");
    }

    @Test
    void theOperatingNumberComesFromTheEntryThenTheMapThenTheBookedNumber() {
        codeshares.save(new CodeshareMapping("KL2842", "XX1", Instant.parse("2026-01-01T00:00:00Z")));

        // 1. The entry's own operating number wins over the map.
        trip(TripStatus.PUBLISHED, entry("KL2842", "BT857", DEPARTS));
        service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "KL2842", DAY);
        assertThat(records.rows).extracting(r -> r.flightNumber()).containsExactly("BT857");

        // 2. With none on the entry, the mapping.
        records.rows.clear();
        trip(TripStatus.PUBLISHED, entry("KL2842", null, DEPARTS));
        service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "KL2842", DAY);
        assertThat(records.rows).extracting(r -> r.flightNumber()).containsExactly("XX1");

        // 3. With neither, the booked number itself.
        records.rows.clear();
        codeshares.rows.clear();
        service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z")).status("trip", "KL2842", DAY);
        assertThat(records.rows).extracting(r -> r.flightNumber()).containsExactly("KL2842");
    }

    @Test
    void aRefreshNeverCallsAviationStackToResolveACodeshare() {
        trip(TripStatus.PUBLISHED, entry("KL2842", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok("");
        CannedHttp stack = new CannedHttp();

        // Inside the live window even, a miss on AeroDataBox stops there.
        var view = service(aero, stack, at("2026-10-24T03:00:00Z")).status("trip", "KL2842", DAY);

        assertThat(view).isEmpty();
        assertThat(stack.callCount()).isZero();
        assertThat(codeshares.rows).isEmpty();
    }

    @Test
    void outsideTheLiveWindowOnlyAeroDataBoxIsCalled() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp();

        // 4.5 hours out: inside the one-day tier, but the window opens two hours before.
        service(aero, stack, at("2026-10-24T00:00:00Z")).status("trip", "BT857", DAY);

        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(stack.callCount()).isZero();
        assertThat(stackUsage.calls("aviationstack", "any")).isZero();
    }

    @Test
    void insideTheWindowLiveExtrasAreFetchedAndStoredOnTheRecord() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp stack = new CannedHttp().ok(LIVE);

        var view = service(new CannedHttp().ok(BT857), stack, at("2026-10-24T03:00:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();

        assertThat(stack.callCount()).isEqualTo(1);
        assertThat(view.live().departure().gate()).isEqualTo("8");
        assertThat(view.live().departure().delayMinutes()).isEqualTo(12);
        assertThat(view.liveFetchedAt()).isEqualTo(Instant.parse("2026-10-24T03:00:00Z"));
        var stored = records.find("BT857", DAY).orElseThrow();
        assertThat(stored.live().arrival().baggageBelt()).isEqualTo("15");
        assertThat(stored.liveFetchedAt()).isEqualTo(Instant.parse("2026-10-24T03:00:00Z"));
    }

    @Test
    void freshLiveExtrasAreNotRefetched() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        service(new CannedHttp().ok(BT857), new CannedHttp().ok(LIVE), at("2026-10-24T03:00:00Z"))
                .status("trip", "BT857", DAY);
        CannedHttp aero = new CannedHttp();
        CannedHttp stack = new CannedHttp();

        var view = service(aero, stack, at("2026-10-24T03:05:00Z")).status("trip", "BT857", DAY).orElseThrow();

        assertThat(aero.callCount()).isZero();
        assertThat(stack.callCount()).isZero();
        assertThat(view.live().departure().gate()).isEqualTo("8");
    }

    @Test
    void aLandedFlightCallsNothing() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        service(new CannedHttp().ok(BT857), new CannedHttp().ok(LIVE), at("2026-10-24T03:00:00Z"))
                .status("trip", "BT857", DAY);
        CannedHttp aero = new CannedHttp();
        CannedHttp stack = new CannedHttp();

        // Scheduled arrival 07:00Z plus the three hour grace has passed.
        var view = service(aero, stack, at("2026-10-24T11:00:00Z")).status("trip", "BT857", DAY);

        assertThat(view).isPresent();
        assertThat(aero.callCount()).isZero();
        assertThat(stack.callCount()).isZero();
    }

    @Test
    void whenAviationStackFailsTheScheduleIsStillReturned() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));

        var view = service(new CannedHttp().ok(BT857), new CannedHttp().status(500, "boom"),
                at("2026-10-24T03:00:00Z")).status("trip", "BT857", DAY).orElseThrow();

        assertThat(view.schedule().departure().airport().iata()).isEqualTo("TLL");
        assertThat(view.live()).isNull();
        assertThat(view.liveFetchedAt()).isNull();
    }

    @Test
    void nothingAvailableAndNothingCachedIsAnEmptyOptional() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));

        assertThat(service(new CannedHttp().status(500, "boom"), new CannedHttp(), at("2026-10-20T10:00:00Z"))
                .status("trip", "BT857", DAY)).isEmpty();
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void ttlSecondsIsTheShorterOfTheScheduleAndLiveTtlWhileLive() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));

        var far = service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();
        records.rows.clear();
        var day = service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-24T00:00:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();
        records.rows.clear();
        var live = service(new CannedHttp().ok(BT857), new CannedHttp().ok(LIVE), at("2026-10-24T03:00:00Z"))
                .status("trip", "BT857", DAY).orElseThrow();

        // F4: fetched 90.5 h out, the answer is kept only until the 72 h boundary, not for the whole 72 h tier.
        assertThat(far.ttlSeconds()).isEqualTo(18 * 3600 + 30 * 60);
        assertThat(day.ttlSeconds()).isEqualTo(3600);
        assertThat(live.ttlSeconds()).isEqualTo(15 * 60);
    }

    // ---- quota guards ----

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(String iso) { now = Instant.parse(iso); }
        void advanceMinutes(long m) { now = now.plusSeconds(m * 60); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private void aStackAnswerIsAskedOnceInFifteenMinutes(String answer, int status) {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T03:00:00Z");
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp().status(status, answer).status(status, answer).status(status, answer);
        FlightStatusService svc = service(aero, stack, clock);

        assertThat(svc.status("trip", "BT857", DAY)).isPresent();
        clock.advanceMinutes(5);
        assertThat(svc.status("trip", "BT857", DAY)).isPresent();
        clock.advanceMinutes(5);
        svc.status("trip", "BT857", DAY);
        assertThat(stack.callCount()).as("a failed attempt still counts").isEqualTo(1);

        clock.advanceMinutes(11);
        svc.status("trip", "BT857", DAY);
        assertThat(stack.callCount()).as("after 15 minutes it asks again").isEqualTo(2);
    }

    @Test
    void aStackNotFoundIsNotAskedAgainWithinFifteenMinutes() {
        aStackAnswerIsAskedOnceInFifteenMinutes("{\"data\":[]}", 200);
    }

    @Test
    void aStack500IsNotAskedAgainWithinFifteenMinutes() {
        aStackAnswerIsAskedOnceInFifteenMinutes("boom", 500);
    }

    @Test
    void aStack200WithAnErrorObjectIsNotAskedAgainWithinFifteenMinutes() {
        aStackAnswerIsAskedOnceInFifteenMinutes("{\"error\":{\"code\":\"usage_limit_reached\"}}", 200);
    }

    @Test
    void anAeroDataBoxOutageOnAnExpiredRecordIsCalledOnceThenServedStale() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-20T10:00:00Z");
        CannedHttp aero = new CannedHttp().ok(BT857).status(500, "boom");
        FlightStatusService svc = service(aero, new CannedHttp(), clock);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).isEqualTo(1);

        clock.now = Instant.parse("2026-10-23T11:00:00Z"); // past the 72 h tier
        var first = svc.status("trip", "BT857", DAY).orElseThrow();
        clock.advanceMinutes(1);
        var second = svc.status("trip", "BT857", DAY).orElseThrow();

        assertThat(aero.callCount()).as("one failed call, the second click is served stale").isEqualTo(2);
        assertThat(first.stale()).isTrue();
        assertThat(second.stale()).isTrue();
        assertThat(second.schedule().departure().airport().iata()).isEqualTo("TLL");
        assertThat(second.ttlSeconds()).isEqualTo(300);

        clock.advanceMinutes(20);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("after the back-off it tries again").isEqualTo(3);
    }

    @Test
    void anOutageWithNothingHeldIsEmptyAndNotRetriedWithinTheBackOff() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-20T10:00:00Z");
        CannedHttp aero = new CannedHttp().status(500, "boom");
        FlightStatusService svc = service(aero, new CannedHttp(), clock);

        assertThat(svc.status("trip", "BT857", DAY)).isEmpty();
        clock.advanceMinutes(2);
        assertThat(svc.status("trip", "BT857", DAY)).isEmpty();
        assertThat(aero.callCount()).isEqualTo(1);
    }

    @Test
    void sixteenSimultaneousReadersMakeOneCallToEachService() throws Exception {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp().ok(LIVE);
        FlightStatusService svc = service(aero, stack, at("2026-10-24T03:00:00Z"));
        int n = 16;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            done.add(pool.submit(() -> {
                start.await();
                svc.status("trip", "BT857", DAY);
                return null;
            }));
        }
        start.countDown();
        for (var f : done) f.get(20, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(stack.callCount()).isEqualTo(1);
    }

    @Test
    void aBadStoredTimezoneFallsBackToUtcInsteadOfFailing() {
        ItineraryItem item = entry("BT857", null, DEPARTS);
        var nowhere = new com.josephinealinea.planner.flights.domain.Airport(
                "TLL", null, null, null, null, "Nowhere/Land", null, null);
        item.setFlight(new FlightSnapshot("BT857", null, nowhere, null, null, null));
        trip(TripStatus.PUBLISHED, item);

        var view = service(new CannedHttp().ok(BT857), new CannedHttp(), at("2026-10-20T10:00:00Z"))
                .status("trip", "BT857", DAY);

        assertThat(view).isPresent();
    }

    // ---- F1: the public path only calls near the flight, and backs off harder on repeated failure ----

    @Test
    void aFlightMoreThanAWeekAwayMakesNoCallEvenWithNothingHeld() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp();

        var view = service(aero, stack, at("2026-10-01T10:00:00Z")).status("trip", "BT857", DAY);

        assertThat(view).isEmpty();
        assertThat(aero.callCount()).isZero();
        assertThat(stack.callCount()).isZero();
    }

    @Test
    void aFlightMoreThanTwoDaysPastMakesNoCall() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok(BT857);

        var view = service(aero, new CannedHttp(), at("2026-10-26T08:00:00Z")).status("trip", "BT857", DAY);

        assertThat(view).isEmpty();
        assertThat(aero.callCount()).isZero();
    }

    @Test
    void outsideTheWindowTheHeldRecordIsServedFreshOrStaleWithNoCall() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        new FlightData(new AeroDataBoxClient(new CannedHttp().ok(BT857).client(), props, new ApiUsageService(aeroUsage),
                        new CircuitBreaker(CircuitBreakerProperties.defaults(), at("2026-10-01T09:00:00Z"))),
                records, new FlightFreshness(props), at("2026-10-01T09:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp aero = new CannedHttp();

        var fresh = service(aero, new CannedHttp(), at("2026-10-01T10:00:00Z")).status("trip", "BT857", DAY).orElseThrow();
        var stale = service(aero, new CannedHttp(), at("2026-10-10T10:00:00Z")).status("trip", "BT857", DAY).orElseThrow();

        assertThat(aero.callCount()).isZero();
        assertThat(fresh.stale()).isFalse();
        assertThat(fresh.schedule().departure().airport().iata()).isEqualTo("TLL");
        assertThat(stale.stale()).as("its 72 h TTL has lapsed").isTrue();
        assertThat(stale.ttlSeconds()).isEqualTo(300);
    }

    @Test
    void failuresBackOffFifteenMinutesThenAnHourThenSixHours() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-23T10:00:00Z");
        CannedHttp aero = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").status(500, "d");
        FlightStatusService svc = service(aero, new CannedHttp(), clock);

        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("first failure").isEqualTo(1);
        clock.advanceMinutes(10);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("within 15 minutes").isEqualTo(1);
        clock.advanceMinutes(10);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("after 15 minutes, second failure").isEqualTo(2);
        clock.advanceMinutes(50);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("within the hour").isEqualTo(2);
        clock.advanceMinutes(11);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("after the hour, third failure").isEqualTo(3);
        clock.advanceMinutes(5 * 60);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("within six hours").isEqualTo(3);
        clock.advanceMinutes(61);
        svc.status("trip", "BT857", DAY);
        assertThat(aero.callCount()).as("after six hours").isEqualTo(4);
    }

    @Test
    void anAnswerResetsTheBackOff() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-23T10:00:00Z");
        CannedHttp aero = new CannedHttp().status(500, "a").ok(BT857).status(500, "b").status(500, "c");
        FlightStatusService svc = service(aero, new CannedHttp(), clock);

        svc.status("trip", "BT857", DAY);              // fails: 15 minutes
        clock.advanceMinutes(20);
        assertThat(svc.status("trip", "BT857", DAY)).isPresent(); // answers: the count resets
        clock.advanceMinutes(61);                       // past the one-hour tier
        svc.status("trip", "BT857", DAY);              // fails: a first failure again
        clock.advanceMinutes(16);
        svc.status("trip", "BT857", DAY);

        assertThat(aero.callCount()).as("15 minutes, not the hour a second failure would earn").isEqualTo(4);
    }

    // ---- F3: AviationStack live extras are capped per flight, whatever number it was booked under ----

    @Test
    void aFlightGetsEightLiveAttemptsAndNoMore() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T02:40:00Z");
        CannedHttp aero = new CannedHttp();
        for (int i = 0; i < 10; i++) aero.ok(BT857);
        CannedHttp stack = new CannedHttp();
        for (int i = 0; i < 9; i++) stack.status(500, "boom");
        // A breaker that never trips, so what is measured is the per-flight cap alone.
        FlightStatusService svc = service(aero, stack, clock, new CircuitBreakerProperties(100, null, null));

        for (int i = 0; i < 8; i++) {
            svc.status("trip", "BT857", DAY);
            clock.advanceMinutes(16);
        }
        assertThat(stack.callCount()).isEqualTo(8);

        svc.status("trip", "BT857", DAY);
        assertThat(stack.callCount()).as("the ninth attempt is not made").isEqualTo(8);
    }

    @Test
    void twoBookedNumbersOnOneOperatingFlightShareOneGuard() {
        trip(TripStatus.PUBLISHED, entry("KL2842", "BT857", DEPARTS), entry("AF1234", "BT857", DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T03:00:00Z");
        CannedHttp stack = new CannedHttp().status(500, "a").status(500, "b");
        FlightStatusService svc = service(new CannedHttp().ok(BT857), stack, clock);

        svc.status("trip", "KL2842", DAY);
        clock.advanceMinutes(5);
        svc.status("trip", "AF1234", DAY);

        assertThat(stack.callCount()).isEqualTo(1);
    }

    @Test
    void theConfiguredLiveAttemptCapIsHonoured() {
        props = new FlightProperties(props.aerodatabox(), props.aviationstack(), null, null, null, null,
                new FlightProperties.PublicRefresh(null, null, 2, null));
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T02:40:00Z");
        CannedHttp aero = new CannedHttp();
        for (int i = 0; i < 5; i++) aero.ok(BT857);
        CannedHttp stack = new CannedHttp().ok("{\"data\":[]}").ok("{\"data\":[]}").ok("{\"data\":[]}");
        FlightStatusService svc = service(aero, stack, clock);

        for (int i = 0; i < 3; i++) {
            svc.status("trip", "BT857", DAY);
            clock.advanceMinutes(16);
        }

        assertThat(stack.callCount()).isEqualTo(2);
    }

    @Test
    void theConfiguredWindowIsHonoured() {
        props = new FlightProperties(props.aerodatabox(), props.aviationstack(), null, null, null, null,
                new FlightProperties.PublicRefresh(null, java.time.Duration.ofDays(30), 0, null));
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS));
        CannedHttp aero = new CannedHttp().ok(BT857);

        var view = service(aero, new CannedHttp(), at("2026-10-01T10:00:00Z")).status("trip", "BT857", DAY);

        assertThat(view).as("23 days ahead is inside a 30-day window").isPresent();
        assertThat(aero.callCount()).isEqualTo(1);
    }

    // ---- the service-wide circuit breaker protects the public path ----

    @Test
    void anAeroDataBoxOutageAcrossThreeFlightsStopsCallsForEveryOtherFlight() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS), entry("LH100", null, DEPARTS),
                entry("LH200", null, DEPARTS), entry("LH300", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-23T10:00:00Z");
        CannedHttp aero = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").ok(BT857);
        FlightStatusService svc = service(aero, new CannedHttp(), clock);

        svc.status("trip", "BT857", DAY);
        svc.status("trip", "LH100", DAY);
        svc.status("trip", "LH200", DAY);
        assertThat(svc.status("trip", "LH300", DAY)).isEmpty();

        assertThat(aero.callCount()).as("the fourth flight is not asked about").isEqualTo(3);
        assertThat(aeroUsage.counts.get("aerodatabox")).as("and spends no quota").isEqualTo(3);

        clock.advanceMinutes(15);
        assertThat(svc.status("trip", "LH300", DAY)).as("after the pause a trial call goes out").isPresent();
        assertThat(aero.callCount()).isEqualTo(4);
    }

    @Test
    void anAviationStackOutageStopsLiveCallsAcrossFlights() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS), entry("LH100", null, DEPARTS),
                entry("LH200", null, DEPARTS), entry("LH300", null, DEPARTS));
        CannedHttp aero = new CannedHttp();
        for (int i = 0; i < 4; i++) aero.ok(BT857);
        CannedHttp stack = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").ok(LIVE);
        FlightStatusService svc = service(aero, stack, at("2026-10-24T03:00:00Z"));

        for (String n : List.of("BT857", "LH100", "LH200", "LH300")) svc.status("trip", n, DAY);

        assertThat(stack.callCount()).isEqualTo(3);
        assertThat(stackUsage.counts.get("aviationstack")).isEqualTo(3);
    }

    // ---- review: a flight is only backed off for a call it actually made ----

    @Test
    void aFlightClickedWhileTheServiceWasPausedIsLookedUpAsSoonAsItRecovers() {
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS), entry("LH100", null, DEPARTS),
                entry("LH200", null, DEPARTS), entry("LH300", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T03:00:00Z");
        CannedHttp aero = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").ok(BT857).ok(BT857);
        CannedHttp stack = new CannedHttp().ok(LIVE).ok(LIVE);
        FlightStatusService svc = service(aero, stack, clock);

        for (String n : List.of("LH100", "LH200", "LH300")) svc.status("trip", n, DAY); // the service opens
        clock.advanceMinutes(1);
        assertThat(svc.status("trip", "BT857", DAY)).as("paused: no call, nothing to show").isEmpty();
        assertThat(aero.callCount()).isEqualTo(3);

        clock.advanceMinutes(14);                        // the pause is over
        assertThat(svc.status("trip", "LH100", DAY)).as("the trial answers").isPresent();
        var view = svc.status("trip", "BT857", DAY);

        assertThat(view).as("not held for a failure it never had").isPresent();
        assertThat(aero.callCount()).isEqualTo(5);
        assertThat(view.orElseThrow().live()).as("and its live extras are fetched").isNotNull();
        assertThat(stack.callCount()).isEqualTo(2);
    }

    @Test
    void aPausedAviationStackDoesNotUseUpAFlightsLiveAttempts() {
        props = new FlightProperties(props.aerodatabox(), props.aviationstack(), null, null, null, null,
                new FlightProperties.PublicRefresh(null, null, 1, null));
        trip(TripStatus.PUBLISHED, entry("BT857", null, DEPARTS), entry("LH100", null, DEPARTS),
                entry("LH200", null, DEPARTS), entry("LH300", null, DEPARTS));
        MutableClock clock = new MutableClock("2026-10-24T03:00:00Z");
        CannedHttp aero = new CannedHttp();
        for (int i = 0; i < 4; i++) aero.ok(BT857);
        CannedHttp stack = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").ok(LIVE);
        FlightStatusService svc = service(aero, stack, clock);

        for (String n : List.of("LH100", "LH200", "LH300")) svc.status("trip", n, DAY); // AviationStack opens
        svc.status("trip", "BT857", DAY);               // paused: must not spend BT857's only attempt
        assertThat(stack.callCount()).isEqualTo(3);

        clock.advanceMinutes(16);
        var view = svc.status("trip", "BT857", DAY).orElseThrow();

        assertThat(stack.callCount()).as("its one attempt is still there").isEqualTo(4);
        assertThat(view.live()).isNotNull();
    }

    /** AV 105 flies BOG-CUZ then CUZ-LPB; an entry saved for the second leg must be shown the second. */
    @Test
    void anEntryIsShownTheLegLeavingItsOwnAirport() {
        ItineraryItem second = entry("AV105", null, LocalDateTime.of(2026, 10, 31, 12, 30));
        var cuzAirport = new com.josephinealinea.planner.flights.domain.Airport("CUZ", null, null, null, null, null, null, null);
        second.setFlight(new FlightSnapshot("AV105", null, cuzAirport, null, null, null));
        trip(TripStatus.PUBLISHED, second);

        var view = service(new CannedHttp().ok(com.josephinealinea.planner.flights.MultiLegFixtures.AV105), new CannedHttp(),
                at("2026-10-30T10:00:00Z")).status("trip", "AV105", java.time.LocalDate.of(2026, 10, 31)).orElseThrow();

        assertThat(view.schedule().departure().airport().iata()).isEqualTo("CUZ");
        assertThat(view.schedule().arrival().airport().iata()).isEqualTo("LPB");
    }
}
