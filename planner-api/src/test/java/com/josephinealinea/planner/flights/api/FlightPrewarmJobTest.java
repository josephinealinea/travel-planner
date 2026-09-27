package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
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

import static com.josephinealinea.planner.flights.api.FlightFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FlightPrewarmJobTest {

    /** Departures below are UTC wall-clock (no airport timezone on the snapshots). */
    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final LocalDateTime SOON = LocalDateTime.of(2026, 10, 22, 7, 30);

    private final Records records = new Records();
    private final Codeshares codeshares = new Codeshares();
    private final Usage usage = new Usage();
    private final TripRepository trips = Mockito.mock(TripRepository.class);
    private final ItineraryRepository itinerary = Mockito.mock(ItineraryRepository.class);
    /** The breaker the last {@code job(...)} was built with. */
    private CircuitBreaker breaker;

    private static FlightProperties props(int limit) {
        return new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", limit, 100, null),
                null, null, null, null, null);
    }

    private void trip(String slug, TripStatus status, ItineraryItem... entries) {
        Trip trip = new Trip();
        trip.setId(slug);
        trip.setSlug(slug);
        trip.setStatus(status);
        Mockito.when(itinerary.findAll(slug)).thenReturn(List.of(entries));
        List<Trip> published = new java.util.ArrayList<>(trips.findAllPublished());
        if (status == TripStatus.PUBLISHED) published.add(trip);
        Mockito.when(trips.findAllPublished()).thenReturn(published);
    }

    private static ItineraryItem entry(String booked, String operating, LocalDateTime start) {
        ItineraryItem item = new ItineraryItem();
        item.setStartAt(start);
        item.setFlight(new FlightSnapshot(booked, operating, null, null, null, null));
        return item;
    }

    private static String payload(String number, String departUtc) {
        return BT857.replace("BT 857", number).replace("2026-10-24 04:30Z", departUtc);
    }

    private FlightPrewarmJob job(CannedHttp http, FlightProperties props) {
        return job(http, props, Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    private static final class MutableClock extends Clock {
        Instant now = NOW;
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private FlightPrewarmJob job(CannedHttp http, FlightProperties props, Clock clock) {
        breaker = new CircuitBreaker(CircuitBreakerProperties.defaults(), clock);
        FlightData data = new FlightData(new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage), breaker),
                records, new FlightFreshness(props), clock);
        return new FlightPrewarmJob(trips, itinerary, codeshares, data, new ApiUsageService(usage), props, breaker, clock);
    }

    @Test
    void onlyPublishedTripsAreWarmed() {
        trip("draft", TripStatus.DRAFT, entry("BT857", null, SOON));
        CannedHttp http = new CannedHttp();

        assertThat(job(http, props(400)).prewarm(NOW)).isZero();
        assertThat(http.callCount()).isZero();
    }

    @Test
    void onlyFlightsWithinSeventyTwoHoursAreWarmed() {
        trip("t", TripStatus.PUBLISHED,
                entry("BT857", null, NOW.plusSeconds(4 * 24 * 3600).atZone(java.time.ZoneOffset.UTC).toLocalDateTime()),
                entry("LH100", null, NOW.plusSeconds(71 * 3600).atZone(java.time.ZoneOffset.UTC).toLocalDateTime()));
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(job(http, props(400)).prewarm(NOW)).isEqualTo(1);
        assertThat(http.callCount()).isEqualTo(1);
        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactly("LH100");
    }

    @Test
    void theSameFlightOnTwoTripsIsOneCall() {
        trip("a", TripStatus.PUBLISHED, entry("BT857", null, SOON));
        trip("b", TripStatus.PUBLISHED, entry("BT857", null, SOON));
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(job(http, props(400)).prewarm(NOW)).isEqualTo(1);
        assertThat(http.callCount()).isEqualTo(1);
    }

    @Test
    void aFlightWithAFreshRecordCostsNoCall() {
        trip("t", TripStatus.PUBLISHED, entry("BT857", null, SOON));
        CannedHttp first = new CannedHttp().ok(BT857);
        job(first, props(400)).prewarm(NOW);
        CannedHttp second = new CannedHttp();

        assertThat(job(second, props(400)).prewarm(NOW)).as("fresh is not counted as warmed").isZero();

        assertThat(second.callCount()).isZero();
    }

    @Test
    void soonestDepartureIsWarmedFirst() {
        // Cap of 1 and one call already spent would block everything; cap 2 with one spent allows exactly one.
        usage.counts.put(AeroDataBoxClient.SERVICE, 1);
        trip("t", TripStatus.PUBLISHED,
                entry("LH200", null, SOON.plusHours(10)),
                entry("LH100", null, SOON));
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(job(http, props(2)).prewarm(NOW)).isEqualTo(1);
        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactly("LH100");
    }

    @Test
    void itStopsAtTheAeroDataBoxCap() {
        usage.counts.put(AeroDataBoxClient.SERVICE, 3);
        trip("t", TripStatus.PUBLISHED, entry("LH100", null, SOON));
        CannedHttp http = new CannedHttp();

        assertThat(job(http, props(3)).prewarm(NOW)).isZero();
        assertThat(http.callCount()).isZero();
    }

    @Test
    void aCodeshareEntryIsWarmedUnderItsOperatingNumber() {
        trip("t", TripStatus.PUBLISHED, entry("KL2842", "BT857", SOON));
        CannedHttp http = new CannedHttp().ok(BT857);

        job(http, props(400)).prewarm(NOW);

        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactly("BT857");
    }

    @Test
    void aCodeshareKnownOnlyByTheMappingIsWarmedUnderItsOperatingNumber() {
        codeshares.save(new com.josephinealinea.planner.flights.domain.CodeshareMapping("KL2842", "BT857", NOW));
        trip("t", TripStatus.PUBLISHED, entry("KL2842", null, SOON));
        CannedHttp http = new CannedHttp().ok(BT857);

        job(http, props(400)).prewarm(NOW);

        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactly("BT857");
    }

    @Test
    void anEntryWithNoFlightIsIgnored() {
        ItineraryItem plain = new ItineraryItem();
        plain.setStartAt(SOON);
        trip("t", TripStatus.PUBLISHED, plain);
        CannedHttp http = new CannedHttp();

        assertThat(job(http, props(400)).prewarm(NOW)).isZero();
        assertThat(http.callCount()).isZero();
    }

    @Test
    void aThrowingLookupDoesNotStopTheRest() {
        trip("t", TripStatus.PUBLISHED, entry("LH100", null, SOON), entry("LH200", null, SOON.plusHours(1)));
        FlightData data = Mockito.mock(FlightData.class);
        Mockito.when(data.schedule(Mockito.eq("LH100"), Mockito.any(), Mockito.any())).thenThrow(new IllegalStateException("boom"));
        Mockito.when(data.schedule(Mockito.eq("LH200"), Mockito.any(), Mockito.any())).thenAnswer(i -> {
            usage.tryAcquire(AeroDataBoxClient.SERVICE, "m", 400);
            return new FlightData.Result(com.josephinealinea.planner.flights.Fetch.Status.FOUND, null, false);
        });
        Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        FlightPrewarmJob job = new FlightPrewarmJob(trips, itinerary, codeshares, data, new ApiUsageService(usage), props(400),
                new CircuitBreaker(CircuitBreakerProperties.defaults(), clock), clock);

        assertThat(job.prewarm(NOW)).isEqualTo(1);
        Mockito.verify(data).schedule(Mockito.eq("LH200"), Mockito.any(), Mockito.any());
    }

    @Test
    void anOutageIsNotCountedAsWarmed() {
        trip("t", TripStatus.PUBLISHED, entry("LH100", null, SOON));
        CannedHttp http = new CannedHttp().status(500, "boom");

        assertThat(job(http, props(400)).prewarm(NOW)).isZero();
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void anUnreadableTimezoneFallsBackToUtcAndTheRestStillWarm() {
        ItineraryItem bad = entry("LH100", null, SOON);
        bad.setFlight(new FlightSnapshot("LH100", null,
                new com.josephinealinea.planner.flights.domain.Airport("XXX", null, null, null, null, "Nowhere/Land", null, null),
                null, null, null));
        trip("t", TripStatus.PUBLISHED, bad, entry("LH200", null, SOON.plusHours(1)));
        CannedHttp http = new CannedHttp().ok(BT857).ok(BT857);

        assertThat(job(http, props(400)).prewarm(NOW)).isEqualTo(2);
        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactlyInAnyOrder("LH100", "LH200");
    }

    @Test
    void aMalformedEntryIsSkippedAndTheRestStillWarm() {
        ItineraryItem broken = new ItineraryItem() {
            @Override public FlightSnapshot getFlight() { throw new IllegalStateException("corrupt"); }
        };
        trip("t", TripStatus.PUBLISHED, broken, entry("LH200", null, SOON));
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(job(http, props(400)).prewarm(NOW)).isEqualTo(1);
        assertThat(records.findAll()).extracting(r -> r.flightNumber()).containsExactly("LH200");
    }

    @Test
    void theRunStopsWhenTheBreakerOpens() {
        trip("t", TripStatus.PUBLISHED,
                entry("LH1", null, SOON), entry("LH2", null, SOON.plusHours(1)), entry("LH3", null, SOON.plusHours(2)),
                entry("LH4", null, SOON.plusHours(3)), entry("LH5", null, SOON.plusHours(4)), entry("LH6", null, SOON.plusHours(5)));
        CannedHttp http = new CannedHttp();
        for (int i = 0; i < 6; i++) http.status(500, "boom");

        assertThat(job(http, props(400)).prewarm(NOW)).isZero();
        assertThat(http.callCount()).as("three failures open the breaker; the run stops there").isEqualTo(3);
        assertThat(usage.counts.get(AeroDataBoxClient.SERVICE)).isEqualTo(3);
        assertThat(breaker.isOpen(AeroDataBoxClient.SERVICE)).isTrue();
    }

    @Test
    void thePrewarmsPeekDoesNotClaimTheTrialSoTheTrialGoesToARealCall() {
        MutableClock clock = new MutableClock();
        trip("t", TripStatus.PUBLISHED, entry("BT857", null, SOON));
        CannedHttp http = new CannedHttp().ok(BT857);
        FlightPrewarmJob job = job(http, props(400), clock);
        for (int i = 0; i < 3; i++) breaker.failure(AeroDataBoxClient.SERVICE);
        clock.now = NOW.plusSeconds(16 * 60); // the 15 minute pause has lapsed

        assertThat(job.prewarm(clock.instant())).as("the trial is a real, successful call").isEqualTo(1);
        assertThat(http.callCount()).isEqualTo(1);
        assertThat(breaker.paused(AeroDataBoxClient.SERVICE)).isFalse();
        assertThat(breaker.isOpen(AeroDataBoxClient.SERVICE)).as("a success closed it").isFalse();
    }

    @Test
    void aBreakerAlreadyOpenMeansNoCallAtAll() {
        trip("t", TripStatus.PUBLISHED, entry("LH1", null, SOON), entry("LH2", null, SOON.plusHours(1)));
        CannedHttp http = new CannedHttp().ok(BT857).ok(BT857);
        FlightPrewarmJob job = job(http, props(400));
        for (int i = 0; i < 3; i++) breaker.failure(AeroDataBoxClient.SERVICE); // an outage seen by readers earlier

        assertThat(job.prewarm(NOW)).isZero();
        assertThat(http.callCount()).isZero();
        assertThat(usage.counts).doesNotContainKey(AeroDataBoxClient.SERVICE);
    }

    @Test
    void aConfiguredThresholdDecidesWhenTheRunStops() {
        trip("t", TripStatus.PUBLISHED,
                entry("LH1", null, SOON), entry("LH2", null, SOON.plusHours(1)), entry("LH3", null, SOON.plusHours(2)));
        CannedHttp http = new CannedHttp().status(500, "a").status(500, "b").status(500, "c");
        Clock clock = Clock.fixed(NOW, java.time.ZoneOffset.UTC);
        CircuitBreaker one = new CircuitBreaker(new CircuitBreakerProperties(null, null,
                java.util.Map.of(AeroDataBoxClient.SERVICE, new CircuitBreakerProperties.Service(1, null))), clock);
        FlightProperties props = props(400);
        FlightData data = new FlightData(new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage), one),
                records, new FlightFreshness(props), clock);

        new FlightPrewarmJob(trips, itinerary, codeshares, data, new ApiUsageService(usage), props, one, clock).prewarm(NOW);

        assertThat(http.callCount()).isEqualTo(1);
    }

    @Test
    void exactlySeventyTwoHoursAndExactlyNowAreIncluded() {
        LocalDateTime edge = NOW.plusSeconds(72 * 3600).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime now = NOW.atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        trip("t", TripStatus.PUBLISHED, entry("LH100", null, edge), entry("LH200", null, now));
        CannedHttp http = new CannedHttp().ok(BT857).ok(BT857);

        assertThat(job(http, props(400)).prewarm(NOW)).isEqualTo(2);
    }

    @Test
    void aFlightThatDepartedOneMinuteAgoIsExcluded() {
        LocalDateTime past = NOW.minusSeconds(60).atZone(java.time.ZoneOffset.UTC).toLocalDateTime();
        trip("t", TripStatus.PUBLISHED, entry("LH100", null, past));
        CannedHttp http = new CannedHttp();

        assertThat(job(http, props(400)).prewarm(NOW)).isZero();
        assertThat(http.callCount()).isZero();
    }
}
