package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FlightFreshnessTest {

    private final FlightFreshness rules =
            new FlightFreshness(new FlightProperties(null, null, null, null, null, null));

    private static final Instant DEPARTURE = Instant.parse("2026-10-24T04:30:00Z");

    private static FlightSchedule schedule(String status, String arrivalUtc) {
        return new FlightSchedule("BT857", status, null, null,
                new FlightSchedule.Leg(null, null, "2026-10-24T04:30Z", null, null, null),
                new FlightSchedule.Leg(null, null, arrivalUtc, null, null, null), null);
    }

    private static FlightRecord record(FlightSchedule schedule, Instant fetched) {
        return new FlightRecord("BT857", LocalDate.of(2026, 10, 24), schedule, null, fetched, null, false);
    }

    @Test
    void theTierFollowsHowCloseTheFlightIs() {
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofDays(10)), DEPARTURE)).isEqualTo(Duration.ofHours(72));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(73)), DEPARTURE)).isEqualTo(Duration.ofHours(72));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(72)), DEPARTURE)).isEqualTo(Duration.ofHours(24));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(25)), DEPARTURE)).isEqualTo(Duration.ofHours(24));
        assertThat(rules.scheduleTtl(DEPARTURE.minus(Duration.ofHours(24)), DEPARTURE)).isEqualTo(Duration.ofHours(1));
        assertThat(rules.scheduleTtl(DEPARTURE.plus(Duration.ofHours(1)), DEPARTURE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void anUnknownDepartureUsesTheLongestTtl() {
        assertThat(rules.scheduleTtl(Instant.now(), null)).isEqualTo(Duration.ofHours(72));
    }

    @Test
    void aRecordIsFreshUntilItsTierTtlLapses() {
        Instant fetched = Instant.parse("2026-10-23T08:00:00Z"); // 22.5 h before: 1 h tier
        FlightRecord r = record(schedule("Expected", "2026-10-24T07:00Z"), fetched);

        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(59)), DEPARTURE)).isTrue();
        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(61)), DEPARTURE)).isFalse();
    }

    @Test
    void aLandedFlightIsFrozenForever() {
        FlightRecord r = record(schedule("Arrived", "2026-10-24T07:00Z"), Instant.parse("2026-10-24T08:00:00Z"));

        assertThat(rules.scheduleFresh(r, Instant.parse("2026-12-01T00:00:00Z"), DEPARTURE)).isTrue();
    }

    @Test
    void landedMeansAnArrivedStatusOrThreeHoursPastTheScheduledArrival() {
        Instant arrival = Instant.parse("2026-10-24T07:00:00Z");

        assertThat(rules.landed(schedule("Arrived", "2026-10-24T07:00Z"), arrival.minus(Duration.ofHours(1)))).isTrue();
        assertThat(rules.landed(schedule("Canceled", "2026-10-24T07:00Z"), arrival.minus(Duration.ofHours(1)))).isTrue();
        assertThat(rules.landed(schedule("EnRoute", "2026-10-24T07:00Z"), arrival.plus(Duration.ofHours(2)))).isFalse();
        assertThat(rules.landed(schedule("EnRoute", "2026-10-24T07:00Z"), arrival.plus(Duration.ofHours(3)))).isTrue();
    }

    @Test
    void theLiveWindowOpensTwoHoursBeforeDeparture() {
        assertThat(rules.inLiveWindow(DEPARTURE.minus(Duration.ofMinutes(121)), DEPARTURE)).isFalse();
        assertThat(rules.inLiveWindow(DEPARTURE.minus(Duration.ofMinutes(119)), DEPARTURE)).isTrue();
        assertThat(rules.inLiveWindow(DEPARTURE.plus(Duration.ofHours(1)), DEPARTURE)).isTrue();
    }

    @Test
    void liveExtrasAreFreshForFifteenMinutes() {
        Instant at = Instant.parse("2026-10-24T03:00:00Z");
        FlightRecord r = new FlightRecord("BT857", LocalDate.of(2026, 10, 24), null, null, null, at, false);

        assertThat(rules.liveFresh(r, at.plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(rules.liveFresh(r, at.plus(Duration.ofMinutes(16)))).isFalse();
        assertThat(rules.liveFresh(new FlightRecord("X", null, null, null, null, null, false), at)).isFalse();
    }

    @Test
    void aMissIsFreshForTheNegativeTtl() {
        Instant at = Instant.parse("2026-10-24T01:00:00Z");
        FlightRecord miss = FlightRecord.missing("KL2842", LocalDate.of(2026, 10, 24), at);

        assertThat(rules.negativeFresh(miss, at.plus(Duration.ofHours(5)))).isTrue();
        assertThat(rules.negativeFresh(miss, at.plus(Duration.ofHours(7)))).isFalse();
    }

    @Test
    void aScheduleWithNoDepartureTimeFallsBackToTheHint() {
        FlightSchedule noDeparture = new FlightSchedule("BT857", "Expected", null, null, null,
                new FlightSchedule.Leg(null, null, "2026-10-24T07:00Z", null, null, null), null);
        Instant fetched = Instant.parse("2026-10-23T08:00:00Z"); // 22.5 h before the hint: the 1 h tier
        FlightRecord r = record(noDeparture, fetched);

        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(61)), DEPARTURE)).as("with the hint").isFalse();
        assertThat(rules.scheduleFresh(r, fetched.plus(Duration.ofMinutes(61)), null)).as("no hint: 72 h tier").isTrue();
    }

    @Test
    void aScheduleWithNoArrivalTimeIsNotLandedByTheClock() {
        Instant muchLater = Instant.parse("2027-01-01T00:00:00Z");
        FlightSchedule noArrival = new FlightSchedule("BT857", "Expected", null, null,
                new FlightSchedule.Leg(null, null, "2026-10-24T04:30Z", null, null, null), null, null);

        assertThat(rules.landed(noArrival, muchLater)).isFalse();
        assertThat(rules.landed(schedule("Expected", null), muchLater)).isFalse();
        assertThat(rules.landed(schedule("Arrived", null), muchLater)).isTrue();
    }

    // ---- F4: how long a reader's browser may keep an answer ----

    private long browserTtl(Instant fetched, Instant now) {
        return rules.browserTtlSeconds(record(schedule("Expected", "2026-10-24T07:00Z"), fetched), now);
    }

    @Test
    void farAheadAnOldRecordIsKeptOnlyForWhatIsLeftOfItsTtl() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        assertThat(browserTtl(now.minus(Duration.ofHours(24)), now)).isEqualTo(Duration.ofHours(48).toSeconds());
    }

    @Test
    void justBeforeTheThreeDayBoundaryItIsKeptUntilTheBoundary() {
        Instant now = DEPARTURE.minus(Duration.ofHours(72)).minus(Duration.ofMinutes(10));
        assertThat(browserTtl(now, now)).isEqualTo(Duration.ofMinutes(10).toSeconds());
    }

    @Test
    void betweenThreeDaysAndOneDayItIsKeptUntilTheOneDayBoundary() {
        Instant now = DEPARTURE.minus(Duration.ofHours(30));
        assertThat(browserTtl(now, now)).isEqualTo(Duration.ofHours(6).toSeconds());
    }

    @Test
    void insideTheOneDayTierItIsWhatIsLeftOfTheHour() {
        Instant now = DEPARTURE.minus(Duration.ofHours(20));
        assertThat(browserTtl(now.minus(Duration.ofMinutes(30)), now)).isEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    void justBeforeTheLiveWindowItIsKeptUntilTheWindowOpens() {
        Instant now = DEPARTURE.minus(Duration.ofHours(2)).minus(Duration.ofMinutes(5));
        assertThat(browserTtl(now, now)).isEqualTo(Duration.ofMinutes(5).toSeconds());
    }

    @Test
    void inTheLiveWindowItIsAtMostTheLiveTtl() {
        Instant now = DEPARTURE.minus(Duration.ofHours(1));
        assertThat(browserTtl(now, now)).isEqualTo(Duration.ofMinutes(15).toSeconds());
        // Near landing, the landing boundary is sooner (07:00Z + 3 h grace).
        Instant nearLanding = Instant.parse("2026-10-24T09:55:00Z");
        assertThat(browserTtl(nearLanding, nearLanding)).isEqualTo(Duration.ofMinutes(5).toSeconds());
    }

    @Test
    void neverBelowAMinute() {
        Instant now = DEPARTURE.minus(Duration.ofHours(72)).minus(Duration.ofSeconds(10));
        assertThat(browserTtl(now, now)).isEqualTo(60);
        Instant later = DEPARTURE.minus(Duration.ofDays(10));
        assertThat(browserTtl(later.minus(Duration.ofDays(5)), later)).as("TTL already lapsed").isEqualTo(60);
    }

    /** The first leg landing must not freeze a second leg that has not flown. */
    @Test
    void aLandedFirstLegDoesNotFreezeALaterLeg() {
        FlightSchedule first = schedule("Arrived", "2026-10-24T07:00Z");
        FlightSchedule second = new FlightSchedule("BT857", "Expected", null, null,
                new FlightSchedule.Leg(null, null, "2026-10-24T08:30Z", null, null, null),
                new FlightSchedule.Leg(null, null, "2026-10-24T10:00Z", null, null, null), null);
        Instant fetched = Instant.parse("2026-10-24T07:30:00Z");
        FlightRecord two = record(first, fetched).withLegs(java.util.List.of(first, second), fetched);

        assertThat(rules.scheduleFresh(two, fetched.plus(Duration.ofHours(2)), DEPARTURE)).isFalse();
        assertThat(rules.scheduleFresh(two, Instant.parse("2026-10-24T12:00:00Z"), DEPARTURE)).isFalse();
        FlightSchedule secondLanded = new FlightSchedule("BT857", "Arrived", null, null,
                second.departure(), second.arrival(), null);
        FlightRecord allDone = record(first, fetched).withLegs(java.util.List.of(first, secondLanded), fetched);
        assertThat(rules.scheduleFresh(allDone, Instant.parse("2026-12-01T00:00:00Z"), DEPARTURE)).isTrue();
    }
}
