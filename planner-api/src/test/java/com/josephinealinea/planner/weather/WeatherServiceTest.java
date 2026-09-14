package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import com.josephinealinea.planner.weather.api.WeatherService;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning destinations into "where the trip is on each of its days", and
 * deciding when that costs a call to Open-Meteo.
 *
 * Two separate jobs are tested here. The **expansion** is pure arithmetic on
 * the destinations' dates and happens whether or not any lookup succeeds: both
 * ends of a range count, so Cusco 25-Oct to 31-Oct is seven rows and a day trip
 * is one, the same reading as the Destinations table's Days column.
 *
 * The **caching** is the part with a rule worth stating out loud: a date in the
 * past is never looked up twice, because the weather on 25 October stops
 * changing once that day is over. Everything else follows from the TTL.
 */
class WeatherServiceTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String USER_ID = "user-1";

    /** "Today" for every test here. Dates before this are the past. */
    private static final LocalDate TODAY = LocalDate.parse("2026-10-27");

    private DestinationRepository destinations;
    private WeatherRepository stored;
    private CannedHttp forecast;
    private CannedHttp climate;
    private WeatherService service;
    private MovableClock clock;
    private Path dir;

    /** A clock a test can wind forward, to age records past the TTL. */
    private static final class MovableClock extends Clock {
        private Instant now = TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC);
        void forward(Duration by) { now = now.plus(by); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dir = tempDir;
        AppProperties props = new AppProperties(
                new AppProperties.Storage(tempDir.toString()),
                new AppProperties.Publish(tempDir.resolve("published").toString(), null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        destinations = new DestinationRepository(store, paths, locks);
        stored = new WeatherRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, "member@example.com", TripRole.OWNER, null));
        trips.save(trip);

        forecast = new CannedHttp();
        climate = new CannedHttp();
        clock = new MovableClock();
        WeatherClient client =
                new WeatherClient(forecast.client(), climate.client(), props, clock);
        service = new WeatherService(destinations, stored, new TripAccessService(trips),
                client, props, clock);
    }

    // ── helpers ─────────────────────────────────────

    private Destination save(String id, String name, String start, String end,
                             Double latitude, Double longitude) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId(TRIP_ID);
        destination.setName(name);
        destination.setCountryCode("PE");
        destination.setCountryFlag("🇵🇪");
        destination.setStartDate(start == null ? null : LocalDate.parse(start));
        destination.setEndDate(end == null ? null : LocalDate.parse(end));
        destination.setLatitude(latitude);
        destination.setLongitude(longitude);
        destination.setSortOrder(destinations.findAll(SLUG).size());
        return destinations.save(SLUG, destination);
    }

    private Destination cusco(String start, String end) {
        return save("cusco", "Cusco", start, end, -13.53188, -71.96701);
    }

    private List<DayWeather> days() {
        return service.forTrip(TRIP_ID, USER_ID);
    }

    /** One answer per date in the range, so a whole stay resolves. */
    private static String answerFor(String... dates) {
        StringBuilder times = new StringBuilder();
        StringBuilder max = new StringBuilder();
        StringBuilder min = new StringBuilder();
        StringBuilder rain = new StringBuilder();
        for (int i = 0; i < dates.length; i++) {
            if (i > 0) { times.append(','); max.append(','); min.append(','); rain.append(','); }
            times.append('"').append(dates[i]).append('"');
            max.append(16 + i);
            min.append(8 + i);
            rain.append("1.5");
        }
        return """
               {"daily":{"time":[%s],"temperature_2m_max":[%s],
                         "temperature_2m_min":[%s],"precipitation_sum":[%s]}}
               """.formatted(times, max, min, rain);
    }

    // ── expansion ───────────────────────────────────

    @Test
    void aStaySpansEveryDayItCoversCountingBothEnds() {
        cusco("2026-10-28", "2026-11-03");
        forecast.ok(answerFor("2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31",
                "2026-11-01", "2026-11-02", "2026-11-03"));

        assertThat(days()).hasSize(7)
                .extracting(DayWeather::date)
                .startsWith(LocalDate.parse("2026-10-28"))
                .endsWith(LocalDate.parse("2026-11-03"));
    }

    @Test
    void aDayTripIsOneRow() {
        cusco("2026-10-28", "2026-10-28");
        forecast.ok(answerFor("2026-10-28"));

        assertThat(days()).hasSize(1);
    }

    @Test
    void aDestinationWithNoDatesHasNoDayToAppearOn() {
        save("nowhere", "Aguas Calientes", null, null, -13.15, -72.52);

        assertThat(days()).isEmpty();
        assertThat(forecast.callCount()).isZero();
    }

    @Test
    void twoDestinationsOnTheSameDayBothGetARow() {
        cusco("2026-10-28", "2026-10-29");
        save("ollanta", "Ollantaytambo", "2026-10-28", "2026-10-28", -13.25722, -72.26306);
        forecast.ok("[" + answerFor("2026-10-28", "2026-10-29") + ","
                + answerFor("2026-10-28", "2026-10-29") + "]");

        assertThat(days()).hasSize(3);
        assertThat(days().stream()
                .filter(day -> day.date().equals(LocalDate.parse("2026-10-28")))
                .map(DayWeather::destinationName))
                .containsExactly("Cusco", "Ollantaytambo");
    }

    /**
     * Some real places have no gazetteer entry — free text always saves and
     * coordinates stay optional — so the row still says where you are and only
     * the weather is missing. Nothing is asked about it.
     */
    @Test
    void aDestinationWithNoCoordinatesStillGetsRowsWithTheWeatherUnavailable() {
        save("nowhere", "Aguas Calientes", "2026-10-28", "2026-10-29", null, null);

        assertThat(days()).hasSize(2).allSatisfy(day ->
                assertThat(day.source()).isEqualTo(DayWeather.Source.UNAVAILABLE));
        assertThat(forecast.callCount()).isZero();
    }

    // ── the past-date rule ──────────────────────────

    /**
     * The rule this whole store exists for. A date that has gone by is answered
     * from the file forever: the weather that day stopped changing when the day
     * ended, so there is nothing a fresh call could tell us.
     */
    @Test
    void aPastDateIsNeverLookedUpAgain() {
        cusco("2026-10-25", "2026-10-26");            // both before TODAY
        forecast.ok(answerFor("2026-10-25", "2026-10-26"));

        assertThat(days()).hasSize(2);
        assertThat(forecast.callCount()).isEqualTo(1);

        // Even a year later, and even with the TTL long lapsed.
        clock.forward(Duration.ofDays(365));
        var later = days();

        assertThat(forecast.callCount()).isEqualTo(1);
        assertThat(later).hasSize(2).allSatisfy(day ->
                assertThat(day.temperatureMax()).isNotNull());
    }

    /**
     * A trip entered after the fact still gets weather once. "Past" is a reason
     * not to ask *again*, not a reason never to ask — and "no record yet" is
     * exactly the case a lookup is for.
     */
    @Test
    void aPastDateWithNoRecordIsStillFetchedOnce() {
        cusco("2026-10-25", "2026-10-25");
        forecast.ok(answerFor("2026-10-25")).ok(answerFor("2026-10-25"));

        assertThat(days()).singleElement()
                .satisfies(day -> assertThat(day.temperatureMax()).isNotNull());
        assertThat(forecast.callCount()).isEqualTo(1);

        // And from then on it falls under the rule above.
        days();
        assertThat(forecast.callCount()).isEqualTo(1);
    }

    /**
     * What is frozen keeps its own label, so a preserved guess never poses as a
     * measurement. A date two days ago is inside the window Open-Meteo
     * forecasts, so this one froze a forecast.
     */
    @Test
    void aFrozenPastReadingKeepsSayingWhereItCameFrom() {
        cusco("2026-10-25", "2026-10-25");
        forecast.ok(answerFor("2026-10-25"));

        assertThat(days()).singleElement()
                .satisfies(day -> assertThat(day.source()).isEqualTo(DayWeather.Source.FORECAST));
    }

    /**
     * Far enough out and the climate model answers instead — which is the
     * normal case for a trip being planned, and the label the page leans on to
     * say "typical" rather than "forecast".
     */
    @Test
    void aDateBeyondTheForecastHorizonIsStoredAsAProjection() {
        cusco("2027-03-01", "2027-03-01");
        climate.ok(answerFor("2027-03-01"));

        assertThat(days()).singleElement()
                .satisfies(day -> assertThat(day.source()).isEqualTo(DayWeather.Source.CLIMATE));
        assertThat(forecast.callCount()).isZero();
        assertThat(climate.callCount()).isEqualTo(1);
    }

    // ── the TTL, for dates still to come ────────────

    @Test
    void aFutureDateIsRefetchedOnceTheTtlLapses() {
        cusco("2026-11-05", "2026-11-05");
        forecast.ok(answerFor("2026-11-05")).ok(answerFor("2026-11-05"));

        days();
        assertThat(forecast.callCount()).isEqualTo(1);

        // Inside the 12h window: still answered from the file.
        clock.forward(Duration.ofHours(6));
        days();
        assertThat(forecast.callCount()).isEqualTo(1);

        // Past it: a forecast for a day still to come genuinely changes.
        clock.forward(Duration.ofHours(7));
        days();
        assertThat(forecast.callCount()).isEqualTo(2);
    }

    @Test
    void aTripWhoseDaysAreAllHeldCostsNoCallAtAll() {
        cusco("2026-11-05", "2026-11-06");
        forecast.ok(answerFor("2026-11-05", "2026-11-06"));

        days();
        days();
        days();

        assertThat(forecast.callCount()).isEqualTo(1);
    }

    /**
     * Adding a destination must not refetch the ones already answered — the
     * point of keying the store per coordinate and day rather than per trip.
     */
    @Test
    void addingADestinationOnlyAsksAboutTheNewOne() {
        cusco("2026-11-05", "2026-11-05");
        forecast.ok(answerFor("2026-11-05")).ok(answerFor("2026-11-05"));
        days();

        save("ollanta", "Ollantaytambo", "2026-11-05", "2026-11-05", -13.25722, -72.26306);
        days();

        assertThat(forecast.callCount()).isEqualTo(2);
        String second = forecast.asked().get(1).toString();
        assertThat(second).contains("latitude=-13.25722");
        assertThat(second).doesNotContain("-13.53188");
    }

    // ── failure ─────────────────────────────────────

    /**
     * A failed lookup is not an error for the page: the days still appear, the
     * weather is simply marked unavailable. Nothing is written, so the next
     * visit tries again rather than trusting a blank for the whole TTL.
     */
    @Test
    void aFailedLookupStillLeavesTheDaysOnTheItinerary() throws Exception {
        cusco("2026-11-05", "2026-11-06");
        forecast.status(429, "{\"error\":true,\"reason\":\"Too many concurrent requests\"}");

        assertThat(days()).hasSize(2).allSatisfy(day ->
                assertThat(day.source()).isEqualTo(DayWeather.Source.UNAVAILABLE));
        assertThat(Files.exists(dir.resolve("travels/weather/" + SLUG + ".yml"))).isFalse();
    }

    /** Stale beats blank once something has been held. */
    @Test
    void anExpiredReadingIsServedWhenTheRefetchFails() {
        cusco("2026-11-05", "2026-11-05");
        forecast.ok(answerFor("2026-11-05"))
               .status(500, "{\"error\":true,\"reason\":\"Something went wrong.\"}")
               .status(500, "{\"error\":true,\"reason\":\"Something went wrong.\"}");

        days();
        clock.forward(Duration.ofHours(13));
        var afterFailure = days();

        assertThat(afterFailure).singleElement()
                .satisfies(day -> assertThat(day.temperatureMax()).isEqualTo(16.0));
    }

    // ── storage ─────────────────────────────────────

    @Test
    void readingsReachTheirOwnFileAndNotTheDestinations() throws Exception {
        cusco("2026-10-28", "2026-10-29");
        forecast.ok(answerFor("2026-10-28", "2026-10-29"));
        days();

        Path file = dir.resolve("travels/weather/" + SLUG + ".yml");
        assertThat(Files.readString(file))
                .contains("date: 2026-10-28")
                .contains("temperatureMax")
                .contains("fetchedAt");

        // The destination record stays about the destination.
        assertThat(Files.readString(dir.resolve("travels/destinations/" + SLUG + ".yml")))
                .doesNotContain("temperatureMax")
                .doesNotContain("fetchedAt");
    }

    @Test
    void aStoredRowIsKeyedByCoordinateSoRenamingADestinationKeepsItsWeather() {
        Destination destination = cusco("2026-11-05", "2026-11-05");
        forecast.ok(answerFor("2026-11-05")).ok(answerFor("2026-11-05"));
        days();

        destination.setName("Cuzco");
        destinations.save(SLUG, destination);
        var afterRename = days();

        assertThat(forecast.callCount()).isEqualTo(1);
        assertThat(afterRename).singleElement().satisfies(day -> {
            assertThat(day.destinationName()).isEqualTo("Cuzco");
            assertThat(day.temperatureMax()).isNotNull();
        });
    }

    @Test
    void theStoredKeyIsCoordinatesAndDate() {
        assertThat(WeatherRecord.keyFor(-13.53188, -71.96701, LocalDate.parse("2026-10-25")))
                .isEqualTo("-13.53,-71.97:2026-10-25");
    }
}
