package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import static com.josephinealinea.planner.flights.api.FlightFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightLookupTest {

    private static final String KL2842 = """
            {"data":[{"flight_date":"2026-09-26","flight_status":"active",
              "departure":{"iata":"TLL","gate":"8","delay":12},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"15","delay":0},
              "flight":{"number":"2842","iata":"KL2842","codeshared":{"airline_iata":"bt",
                "flight_number":"857","flight_iata":"bt857"}}}]}
            """;

    private static final String PLAIN = """
            {"data":[{"flight_date":"2026-09-26","flight_status":"scheduled",
              "departure":{"iata":"TLL"},"arrival":{"iata":"AMS"},
              "flight":{"number":"2842","iata":"KL2842","codeshared":null}}]}
            """;

    private final FlightProperties props = new FlightProperties(
            new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
            new FlightProperties.Service("http://api.aviationstack.com/v1", "key", 100, 90, null),
            null, null, null, null);
    private final Records records = new Records();
    private final Codeshares codeshares = new Codeshares();
    private final Usage aeroUsage = new Usage();
    private final Usage stackUsage = new Usage();
    /** The breaker the last {@code lookup(...)} was built with, on that lookup's clock. */
    private CircuitBreaker breaker;

    private FlightLookup lookup(CannedHttp aero, CannedHttp stack) {
        return lookup(aero, stack, at("2026-10-01T10:00:00Z"));
    }

    private FlightLookup lookup(CannedHttp aero, CannedHttp stack, java.time.Clock clock) {
        breaker = new CircuitBreaker(CircuitBreakerProperties.defaults(), clock);
        AeroDataBoxClient aeroClient = new AeroDataBoxClient(aero.client(), props, new ApiUsageService(aeroUsage), breaker);
        AviationStackClient stackClient = new AviationStackClient(stack.client(), props, new ApiUsageService(stackUsage), breaker);
        FlightData data = new FlightData(aeroClient, records, new FlightFreshness(props), clock);
        return new FlightLookup(data, stackClient, codeshares, clock);
    }

    @Test
    void aDirectHitFillsFromAeroDataBoxAndSpendsNoAviationStackCall() {
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp();

        FlightLookup.Outcome out = lookup(aero, stack).lookup("BT857", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(out.operatingNumber()).isNull();
        assertThat(out.schedule().departure().airport().iata()).isEqualTo("TLL");
        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(stack.callCount()).isZero();
    }

    /** The KL2842 story. */
    @Test
    void aCodeshareIsResolvedThenLookedUpAsItsOperatingFlight() {
        CannedHttp aero = new CannedHttp().ok("").ok(BT857);
        CannedHttp stack = new CannedHttp().ok(KL2842);

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(out.operatingNumber()).isEqualTo("BT857");
        assertThat(codeshares.operatingFor("KL2842")).contains("BT857");
        assertThat(aero.callCount()).isEqualTo(2);
        assertThat(stack.callCount()).isEqualTo(1);
    }

    @Test
    void aStoredMappingSkipsAviationStack() {
        codeshares.save(new com.josephinealinea.planner.flights.domain.CodeshareMapping(
                "KL2842", "BT857", java.time.Instant.parse("2026-09-01T00:00:00Z")));
        CannedHttp aero = new CannedHttp().ok(BT857);
        CannedHttp stack = new CannedHttp();

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(out.operatingNumber()).isEqualTo("BT857");
        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(aero.asked().get(0).toString()).contains("BT857");
        assertThat(stack.callCount()).isZero();
    }

    @Test
    void aMissOnBothIsNotFoundAndTheBookedNumberMissIsCached() {
        CannedHttp aero = new CannedHttp().ok("");
        CannedHttp stack = new CannedHttp().ok(PLAIN);

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(out.schedule()).isNull();
        assertThat(codeshares.findAll()).isEmpty();
        assertThat(records.find("KL2842", DAY).orElseThrow().notFound()).isTrue();
    }

    /** Review focus 6. */
    @Test
    void anAviationStackOutageAfterAMissIsUnavailableNotNotFound() {
        CannedHttp aero = new CannedHttp().ok("");
        CannedHttp stack = new CannedHttp().status(500, "boom");

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(codeshares.findAll()).isEmpty();
    }

    @Test
    void anAeroDataBoxOutageIsUnavailable() {
        CannedHttp aero = new CannedHttp().status(500, "boom");
        CannedHttp stack = new CannedHttp();

        FlightLookup.Outcome out = lookup(aero, stack).lookup("BT857", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(stack.callCount()).isZero();
    }

    /** Review focus 1. */
    @Test
    void lowerCaseAndSpacesAreTheSameFlight() {
        CannedHttp aero = new CannedHttp().ok("").ok(BT857);
        CannedHttp stack = new CannedHttp().ok(KL2842);

        FlightLookup.Outcome out = lookup(aero, stack).lookup("kl 2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(codeshares.rows).containsOnlyKeys("KL2842");
        assertThat(records.find("KL2842", DAY)).isPresent();
    }

    @Test
    void aMalformedNumberIsRefusedBeforeAnyCall() {
        CannedHttp aero = new CannedHttp();
        CannedHttp stack = new CannedHttp();
        FlightLookup lookup = lookup(aero, stack);

        assertThatThrownBy(() -> lookup.lookup("hello", DAY))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("error.flight.numberInvalid");
        assertThat(aero.callCount()).isZero();
        assertThat(stack.callCount()).isZero();
    }

    @Test
    void aCodeshareThatResolvesToItselfIsIgnored() {
        CannedHttp aero = new CannedHttp().ok("");
        CannedHttp stack = new CannedHttp().ok(KL2842.replace("bt857", "kl2842"));

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(codeshares.findAll()).isEmpty();
    }

    @Test
    void snapshotCarriesAirportsTerminalsAndBothNumbers() {
        String withTerminals = BT857
                .replace("\"scheduledTime\":{\"utc\":\"2026-10-24 04:30Z\"", "\"terminal\":\"1\",\"scheduledTime\":{\"utc\":\"2026-10-24 04:30Z\"")
                .replace("\"scheduledTime\":{\"utc\":\"2026-10-24 07:00Z\"", "\"terminal\":\"2\",\"scheduledTime\":{\"utc\":\"2026-10-24 07:00Z\"");
        FlightLookup.Outcome out = lookup(new CannedHttp().ok(withTerminals), new CannedHttp()).lookup("BT857", DAY);

        FlightSnapshot snap = FlightLookup.snapshotOf("kl 2842", "BT857", out.schedule());

        assertThat(snap.number()).isEqualTo("KL2842");
        assertThat(snap.operatingNumber()).isEqualTo("BT857");
        assertThat(snap.from().iata()).isEqualTo("TLL");
        assertThat(snap.to().iata()).isEqualTo("AMS");
        assertThat(snap.terminalFrom()).isEqualTo("1");
        assertThat(snap.terminalTo()).isEqualTo("2");
        assertThat(snap.airline()).isEqualTo("airBaltic");
    }

    // ---- F2: a stored miss never spends AviationStack again ----

    @Test
    void repeatingAnUnknownNumberAsksAviationStackOnceWithinTheNegativeTtl() {
        CannedHttp aero = new CannedHttp().ok("");
        CannedHttp stack = new CannedHttp().ok(PLAIN).ok(PLAIN).ok(PLAIN);

        FlightLookup first = lookup(aero, stack, at("2026-10-01T10:00:00Z"));
        assertThat(first.lookup("KL2842", DAY).status()).isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(lookup(aero, stack, at("2026-10-01T11:00:00Z")).lookup("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(lookup(aero, stack, at("2026-10-01T15:00:00Z")).lookup("KL2842", DAY).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);

        assertThat(aero.callCount()).isEqualTo(1);
        assertThat(stack.callCount()).isEqualTo(1);
    }

    @Test
    void afterTheNegativeTtlBothServicesAreAskedAgain() {
        CannedHttp aero = new CannedHttp().ok("").ok("");
        CannedHttp stack = new CannedHttp().ok(PLAIN).ok(PLAIN);

        lookup(aero, stack, at("2026-10-01T10:00:00Z")).lookup("KL2842", DAY);
        lookup(aero, stack, at("2026-10-01T17:00:00Z")).lookup("KL2842", DAY); // 7 h: past the 6 h negative TTL

        assertThat(aero.callCount()).isEqualTo(2);
        assertThat(stack.callCount()).isEqualTo(2);
    }

    @Test
    void aCodeshareWhoseOperatingFlightMissesIsNotFoundAndNotRemembered() {
        CannedHttp aero = new CannedHttp().ok("").ok("");
        CannedHttp stack = new CannedHttp().ok(KL2842);

        FlightLookup.Outcome out = lookup(aero, stack).lookup("KL2842", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(aero.callCount()).isEqualTo(2);
        assertThat(codeshares.findAll()).isEmpty();
    }

    @Test
    void aCacheServedFoundNeverCallsAviationStack() {
        lookup(new CannedHttp().ok(BT857), new CannedHttp()).lookup("BT857", DAY);
        CannedHttp aero = new CannedHttp();
        CannedHttp stack = new CannedHttp();

        FlightLookup.Outcome out = lookup(aero, stack, at("2026-10-01T11:00:00Z")).lookup("BT857", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(aero.callCount()).isZero();
        assertThat(stack.callCount()).isZero();
    }

    /** F5 seen from the form: an empty answer over a known flight is not a reason to spend AviationStack. */
    @Test
    void anEmptyAnswerOverAKnownFlightServesItAndSpendsNoAviationStackCall() {
        lookup(new CannedHttp().ok(BT857), new CannedHttp()).lookup("BT857", DAY);
        CannedHttp stack = new CannedHttp();

        FlightLookup.Outcome out = lookup(new CannedHttp().ok(""), stack, at("2026-10-05T10:00:00Z")).lookup("BT857", DAY);

        assertThat(out.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(stack.callCount()).isZero();
    }

    // ---- the service-wide circuit breaker protects the member's form too ----

    @Test
    void anAeroDataBoxOutageStopsTheFormSpendingCalls() {
        CannedHttp aero = new CannedHttp().status(500, "a").status(500, "b").status(500, "c").ok(BT857);
        CannedHttp stack = new CannedHttp();
        FlightLookup lookup = lookup(aero, stack);

        for (String n : new String[] {"BT857", "LH100", "LH200"}) lookup.lookup(n, DAY);
        FlightLookup.Outcome fourth = lookup.lookup("LH300", DAY);

        assertThat(fourth.status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(aero.callCount()).isEqualTo(3);
        assertThat(aeroUsage.counts.get("aerodatabox")).isEqualTo(3);
        assertThat(stack.callCount()).as("an outage is not a miss, so no codeshare question").isZero();
        assertThat(breaker.isOpen("aerodatabox")).isTrue();
    }
}
