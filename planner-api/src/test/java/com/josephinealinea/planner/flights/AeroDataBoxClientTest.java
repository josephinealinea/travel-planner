package com.josephinealinea.planner.flights;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AeroDataBoxClientTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    /** Real payload, trimmed of the fields the app does not read. */
    private static final String BT857 = """
            [{"greatCircleDistance":{"km":1476.01},
              "departure":{"airport":{"icao":"EETN","iata":"TLL","name":"Tallinn Lennart Meri","shortName":"Lennart Meri",
                  "municipalityName":"Tallinn","location":{"lat":59.4133,"lon":24.8328},"countryCode":"EE","timeZone":"Europe/Tallinn"},
                "scheduledTime":{"utc":"2026-10-24 04:30Z","local":"2026-10-24 07:30+03:00"},"quality":["Basic"]},
              "arrival":{"airport":{"icao":"EHAM","iata":"AMS","name":"Amsterdam Schiphol","shortName":"Schiphol",
                  "municipalityName":"Amsterdam","location":{"lat":52.3086,"lon":4.763889},"countryCode":"NL","timeZone":"Europe/Amsterdam"},
                "scheduledTime":{"utc":"2026-10-24 07:00Z","local":"2026-10-24 09:00+02:00"},
                "predictedTime":{"utc":"2026-10-24 06:56Z","local":"2026-10-24 08:56+02:00"},"quality":["Basic"]},
              "lastUpdatedUtc":"2026-06-02 08:13Z","number":"BT 857","status":"Expected","codeshareStatus":"Unknown",
              "isCargo":false,"aircraft":{"model":"Airbus A220-300"},"airline":{"name":"airBaltic","iata":"BT","icao":"BTI"}}]
            """;

    /** Counts real acquisitions so a test can see what the client spent. */
    private static final class Counting implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service, 0);
            if (now >= cap) return false;
            counts.put(service, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service, 0);
        }
    }

    private final Counting usage = new Counting();
    private final CircuitBreaker breaker =
            new CircuitBreaker(CircuitBreakerProperties.defaults(), java.time.Clock.systemUTC());

    private AeroDataBoxClient client(CannedHttp http, String key) {
        FlightProperties props = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", key, 400, 90, null),
                null, null, null, null, null);
        return new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage), breaker);
    }

    @Test
    void readsAFlightIncludingAirportsTimesAndCoordinates() {
        CannedHttp http = new CannedHttp().ok(BT857);

        Fetch<FlightSchedule> result = client(http, "key").flight("BT857", DAY);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        FlightSchedule flight = result.value();
        assertThat(flight.number()).isEqualTo("BT857");
        assertThat(flight.aircraftModel()).isEqualTo("Airbus A220-300");
        assertThat(flight.departure().airport().iata()).isEqualTo("TLL");
        assertThat(flight.departure().airport().city()).isEqualTo("Tallinn");
        assertThat(flight.departure().airport().timezone()).isEqualTo("Europe/Tallinn");
        assertThat(flight.departure().airport().lat()).isEqualTo(59.4133);
        assertThat(flight.departure().scheduledLocal()).isEqualTo("2026-10-24T07:30+03:00");
        assertThat(flight.departure().scheduledUtc()).isEqualTo("2026-10-24T04:30Z");
        assertThat(flight.arrival().predictedLocal()).isEqualTo("2026-10-24T08:56+02:00");
        assertThat(http.asked().get(0).toString()).contains("/flights/number/BT857/2026-10-24")
                .contains("dateLocalRole=Departure");
    }

    /** The service answers "no such flight" with an empty body, not an error. */
    @Test
    void anEmptyBodyIsNotFound() {
        assertThat(client(new CannedHttp().ok(""), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(client(new CannedHttp().ok("[]"), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(client(new CannedHttp().status(204, ""), "key").flight("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void aFlightOnAnotherDateIsNotFound() {
        assertThat(client(new CannedHttp().ok(BT857), "key").flight("BT857", DAY.plusDays(1)).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    /** Review focus 6: these are outages, never misses. */
    @Test
    void throttlingAndServerErrorsAreUnavailable() {
        assertThat(client(new CannedHttp().status(429, "{\"message\":\"Too many requests\"}"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(500, "boom"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(403, "{}"), "key")
                .flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    @Test
    void noKeyMeansNoCallAndNothingSpent() {
        CannedHttp http = new CannedHttp().ok(BT857);

        assertThat(client(http, "").flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isZero();
        assertThat(usage.counts).isEmpty();
    }

    @Test
    void theCapStopsTheCall() {
        FlightProperties props = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 1, 100, null),
                null, null, null, null, null);
        CannedHttp http = new CannedHttp().ok(BT857).ok(BT857);
        AeroDataBoxClient client = new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage), breaker);

        assertThat(client.flight("BT857", DAY).status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(client.flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isEqualTo(1);
    }

    @Test
    void aNonArrayBodyIsUnavailableNotAMiss() {
        assertThat(client(new CannedHttp().ok("{\"message\":\"x\"}"), "key").flight("BT857", DAY).status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().ok("null"), "key").flight("BT857", DAY).status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    @Test
    void entriesWithoutADepartureTimeAreUnavailable() {
        assertThat(client(new CannedHttp().ok("[{\"number\":\"BT 857\"}]"), "key").flight("BT857", DAY).status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    // ---- the circuit breaker ----

    @Test
    void threeFailedCallsOpenTheBreakerAndThenNothingIsCalledOrSpent() {
        CannedHttp http = new CannedHttp().status(500, "a").status(429, "b").ok("{\"message\":\"x\"}").ok(BT857);
        AeroDataBoxClient client = client(http, "key");

        for (int i = 0; i < 3; i++) client.flight("BT857", DAY);
        assertThat(breaker.isOpen(AeroDataBoxClient.SERVICE)).isTrue();

        assertThat(client.flight("BT857", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client.flight("LH100", DAY).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).as("no outbound call while open").isEqualTo(3);
        assertThat(usage.counts.get(AeroDataBoxClient.SERVICE)).as("no quota spent while open").isEqualTo(3);
    }

    @Test
    void aNotFoundIsAnAnswerAndResetsTheStreak() {
        CannedHttp http = new CannedHttp().status(500, "a").status(500, "b").ok("").status(500, "c").status(500, "d");
        AeroDataBoxClient client = client(http, "key");

        for (int i = 0; i < 5; i++) client.flight("BT857", DAY);

        assertThat(breaker.isOpen(AeroDataBoxClient.SERVICE)).isFalse();
        assertThat(http.callCount()).isEqualTo(5);
    }

    @Test
    void noKeyCapReachedAndAnOpenBreakerAreNeverReportedAsFailures() {
        CircuitBreaker spy = org.mockito.Mockito.spy(breaker);
        CannedHttp http = new CannedHttp().status(500, "a").status(500, "b").status(500, "c");
        FlightProperties keyed = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
                null, null, null, null, null);
        FlightProperties keyless = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "", 400, 90, null),
                null, null, null, null, null);
        FlightProperties capped = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 0, 90, null),
                null, null, null, null, null);

        for (int i = 0; i < 5; i++) new AeroDataBoxClient(http.client(), keyless, new ApiUsageService(usage), spy).flight("BT857", DAY);
        for (int i = 0; i < 5; i++) new AeroDataBoxClient(http.client(), capped, new ApiUsageService(usage), spy).flight("BT857", DAY);
        org.mockito.Mockito.verify(spy, org.mockito.Mockito.never()).failure(AeroDataBoxClient.SERVICE);

        AeroDataBoxClient client = new AeroDataBoxClient(http.client(), keyed, new ApiUsageService(usage), spy);
        for (int i = 0; i < 8; i++) client.flight("BT857", DAY);
        org.mockito.Mockito.verify(spy, org.mockito.Mockito.times(3)).failure(AeroDataBoxClient.SERVICE);
        assertThat(http.callCount()).isEqualTo(3);
    }

    @Test
    void aFoundAnswerIsReportedAsASuccess() {
        CircuitBreaker spy = org.mockito.Mockito.spy(breaker);
        FlightProperties keyed = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
                null, null, null, null, null);

        new AeroDataBoxClient(new CannedHttp().ok(BT857).client(), keyed, new ApiUsageService(usage), spy).flight("BT857", DAY);

        org.mockito.Mockito.verify(spy).success(AeroDataBoxClient.SERVICE);
    }

    @Test
    void aNumberFlyingTwoLegsThatDayReturnsBothInDepartureOrder() {
        var legs = client(new CannedHttp().ok(com.josephinealinea.planner.flights.MultiLegFixtures.AV105), "key")
                .legs("AV105", java.time.LocalDate.of(2026, 10, 31));

        assertThat(legs.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(legs.value()).extracting(l -> l.departure().airport().iata()).containsExactly("BOG", "CUZ");
        assertThat(legs.value()).extracting(l -> l.arrival().airport().iata()).containsExactly("CUZ", "LPB");
    }

    @Test
    void theSingleLegCallStillAnswersWithTheFirstLeg() {
        var first = client(new CannedHttp().ok(com.josephinealinea.planner.flights.MultiLegFixtures.AV105), "key")
                .flight("AV105", java.time.LocalDate.of(2026, 10, 31));

        assertThat(first.value().departure().airport().iata()).isEqualTo("BOG");
    }
}
