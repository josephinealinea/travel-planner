package com.josephinealinea.planner.flights.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightRecordLegsTest {

    private static FlightSchedule leg(String from, String to) {
        return new FlightSchedule("AV105", "Expected", null, null,
                new FlightSchedule.Leg(new Airport(from, null, null, null, null, null, null, null), null, null, null, null, null),
                new FlightSchedule.Leg(new Airport(to, null, null, null, null, null, null, null), null, null, null, null, null), null);
    }

    private final FlightRecord record = new FlightRecord("AV105", LocalDate.of(2026, 10, 31), null, null, null, null, false)
            .withLegs(List.of(leg("BOG", "CUZ"), leg("CUZ", "LPB")), Instant.parse("2026-09-27T05:40:00Z"));

    @Test
    void theFirstLegStaysWhereOlderRecordsKeptTheirOnlyOne() {
        assertThat(record.schedule().departure().airport().iata()).isEqualTo("BOG");
        assertThat(record.otherLegs()).hasSize(1);
        assertThat(record.allLegs()).hasSize(2);
    }

    @Test
    void anEntryGetsTheLegLeavingItsOwnAirport() {
        assertThat(record.narrowedTo("CUZ").schedule().arrival().airport().iata()).isEqualTo("LPB");
        assertThat(record.narrowedTo("cuz").schedule().arrival().airport().iata()).isEqualTo("LPB");
    }

    @Test
    void anUnknownOrMissingAirportFallsBackToTheFirstLeg() {
        assertThat(record.narrowedTo(null).schedule().departure().airport().iata()).isEqualTo("BOG");
        assertThat(record.narrowedTo("XXX").schedule().departure().airport().iata()).isEqualTo("BOG");
    }

    @Test
    void oneLegIsStoredWithNoOtherLegsAtAll() {
        FlightRecord one = record.withLegs(List.of(leg("BOG", "CUZ")), Instant.now());
        assertThat(one.otherLegs()).isNull();
        assertThat(one.narrowedTo("CUZ")).isSameAs(one);
    }
}
