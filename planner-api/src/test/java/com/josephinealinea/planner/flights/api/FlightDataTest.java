package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static com.josephinealinea.planner.flights.api.FlightFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class FlightDataTest {

    private final FlightProperties props = new FlightProperties(
            new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
            null, null, null, null, null);
    private final Records records = new Records();
    private final Usage usage = new Usage();

    private FlightData data(CannedHttp http, Clock clock) {
        AeroDataBoxClient client = new AeroDataBoxClient(http.client(), props, new ApiUsageService(usage),
                new CircuitBreaker(CircuitBreakerProperties.defaults(), clock));
        return new FlightData(client, records, new FlightFreshness(props), clock);
    }

    @Test
    void aMissingRecordIsFetchedAndStored() {
        CannedHttp http = new CannedHttp().ok(BT857);

        FlightData.Result result = data(http, at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.stale()).isFalse();
        assertThat(http.callCount()).isEqualTo(1);
        assertThat(records.find("BT857", DAY)).isPresent();
        assertThat(records.find("BT857", DAY).orElseThrow().scheduleFetchedAt())
                .isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    @Test
    void aFreshRecordIsServedWithoutACall() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp second = new CannedHttp();

        // 23 days out is the 72 h tier: an hour later it is still fresh.
        FlightData.Result result = data(second, at("2026-10-01T11:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(second.callCount()).isZero();
    }

    @Test
    void aStaleRecordIsRefetchedAndReplaced() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp second = new CannedHttp().ok(BT857);

        // 73 h later: past the 72 h far-ahead TTL.
        data(second, at("2026-10-04T11:00:00Z")).schedule("BT857", DAY, null);

        assertThat(second.callCount()).isEqualTo(1);
        assertThat(records.find("BT857", DAY).orElseThrow().scheduleFetchedAt())
                .isEqualTo(Instant.parse("2026-10-04T11:00:00Z"));
    }

    @Test
    void aLandedRecordIsNeverRefetched() {
        String arrived = BT857.replace("\"status\":\"Expected\"", "\"status\":\"Arrived\"");
        data(new CannedHttp().ok(arrived), at("2026-10-24T08:00:00Z")).schedule("BT857", DAY, null);
        CannedHttp later = new CannedHttp();

        FlightData.Result result = data(later, at("2026-12-01T00:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(later.callCount()).isZero();
    }

    @Test
    void aGenuineEmptyAnswerIsStoredAsAMissAndNotAskedAgain() {
        CannedHttp first = new CannedHttp().ok("");
        assertThat(data(first, at("2026-10-01T10:00:00Z")).schedule("KL2842", DAY, null).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);

        CannedHttp second = new CannedHttp();
        assertThat(data(second, at("2026-10-01T12:00:00Z")).schedule("KL2842", DAY, null).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
        assertThat(second.callCount()).as("within the negative TTL").isZero();
        assertThat(records.find("KL2842", DAY).orElseThrow().notFound()).isTrue();
    }

    @Test
    void aMissExpiresAfterTheNegativeTtl() {
        data(new CannedHttp().ok(""), at("2026-10-01T10:00:00Z")).schedule("KL2842", DAY, null);
        CannedHttp again = new CannedHttp().ok(BT857.replace("BT 857", "KL 2842"));

        // The default negative TTL is 6 h; 7 h later it is asked again.
        FlightData.Result result = data(again, at("2026-10-01T17:00:00Z")).schedule("KL2842", DAY, null);

        assertThat(again.callCount()).isEqualTo(1);
        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
    }

    /** Review focus 6: a 500 is an outage, and an outage must never look like "no such flight". */
    @Test
    void anOutageIsNeverStoredAsAMiss() {
        FlightData.Result result = data(new CannedHttp().status(500, "boom"), at("2026-10-01T10:00:00Z"))
                .schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(records.findAll()).isEmpty();
    }

    @Test
    void anOutageServesTheStaleRecordWhenThereIsOne() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);

        FlightData.Result result = data(new CannedHttp().status(500, "boom"), at("2026-10-04T11:00:00Z"))
                .schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.stale()).isTrue();
        assertThat(result.record().scheduleFetchedAt()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }

    // ---- F2: whether this request actually asked AeroDataBox ----

    @Test
    void fromCacheSaysWhetherTheServiceWasAsked() {
        assertThat(data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null).fromCache())
                .as("fetched").isFalse();
        assertThat(data(new CannedHttp(), at("2026-10-01T11:00:00Z")).schedule("BT857", DAY, null).fromCache())
                .as("fresh record").isTrue();
        assertThat(data(new CannedHttp().ok(""), at("2026-10-01T10:00:00Z")).schedule("KL2842", DAY, null).fromCache())
                .as("genuine empty answer").isFalse();
        assertThat(data(new CannedHttp(), at("2026-10-01T11:00:00Z")).schedule("KL2842", DAY, null).fromCache())
                .as("stored miss").isTrue();
        assertThat(data(new CannedHttp().status(500, "boom"), at("2026-10-04T11:00:00Z")).schedule("BT857", DAY, null).fromCache())
                .as("stale served after an outage").isTrue();
        assertThat(data(new CannedHttp().status(500, "boom"), at("2026-10-04T11:00:00Z")).schedule("XX1", DAY, null).fromCache())
                .as("outage with nothing held: it was asked").isFalse();
    }

    // ---- F5: one bad empty answer never blanks a known flight ----

    private static final com.josephinealinea.planner.flights.domain.FlightLive GATE = new com.josephinealinea.planner.flights.domain.FlightLive(
            "scheduled", new com.josephinealinea.planner.flights.domain.FlightLive.Leg("8", null, 12, null), null);

    @Test
    void anEmptyAnswerOverAGoodRecordServesItStaleAndChangesNothing() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        records.save(records.find("BT857", DAY).orElseThrow().withLive(GATE, Instant.parse("2026-10-01T10:05:00Z")));
        var before = records.find("BT857", DAY).orElseThrow();

        FlightData.Result result = data(new CannedHttp().ok(""), at("2026-10-04T11:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.stale()).isTrue();
        assertThat(result.fromCache()).isTrue();
        assertThat(result.record()).isEqualTo(before);
        assertThat(records.find("BT857", DAY).orElseThrow()).isEqualTo(before);
    }

    /** Unreachable through the freshness rules (a landed record is always fresh) except for a hand-edited row with no fetch time. */
    @Test
    void anEmptyAnswerOverALandedRecordAlsoKeepsIt() {
        var arrived = new com.josephinealinea.planner.flights.domain.FlightRecord("BT857", DAY,
                new com.josephinealinea.planner.flights.domain.FlightSchedule("BT857", "Arrived", null, null, null, null, null),
                null, null, null, false);
        records.save(arrived);

        FlightData.Result result = data(new CannedHttp().ok(""), at("2026-10-25T10:00:00Z")).schedule("BT857", DAY, null);

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(records.find("BT857", DAY).orElseThrow()).isEqualTo(arrived);
    }

    @Test
    void aFoundAnswerAfterAStoredMissClearsTheMiss() {
        data(new CannedHttp().ok(""), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);

        data(new CannedHttp().ok(BT857), at("2026-10-01T17:00:00Z")).schedule("BT857", DAY, null);

        var stored = records.find("BT857", DAY).orElseThrow();
        assertThat(stored.notFound()).isFalse();
        assertThat(stored.schedule()).isNotNull();
    }

    @Test
    void aRefetchKeepsTheLiveExtras() {
        data(new CannedHttp().ok(BT857), at("2026-10-01T10:00:00Z")).schedule("BT857", DAY, null);
        Instant liveAt = Instant.parse("2026-10-01T10:05:00Z");
        records.save(records.find("BT857", DAY).orElseThrow().withLive(GATE, liveAt));

        data(new CannedHttp().ok(BT857), at("2026-10-04T11:00:00Z")).schedule("BT857", DAY, null);

        var stored = records.find("BT857", DAY).orElseThrow();
        assertThat(stored.scheduleFetchedAt()).isEqualTo(Instant.parse("2026-10-04T11:00:00Z"));
        assertThat(stored.live()).isEqualTo(GATE);
        assertThat(stored.liveFetchedAt()).isEqualTo(liveAt);
    }
}
