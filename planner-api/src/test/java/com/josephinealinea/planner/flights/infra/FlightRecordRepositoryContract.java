package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class FlightRecordRepositoryContract {

    protected abstract FlightRecordRepository repository();

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    private static String number() {
        return "T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    static FlightSchedule schedule() {
        Airport tll = new Airport("TLL", "EETN", "Tallinn Lennart Meri", "Tallinn", "EE", "Europe/Tallinn", 59.4133, 24.8328);
        Airport ams = new Airport("AMS", "EHAM", "Amsterdam Schiphol", "Amsterdam", "NL", "Europe/Amsterdam", 52.3086, 4.7639);
        return new FlightSchedule("BT 857", "Expected", "Airbus A220-300", "airBaltic",
                new FlightSchedule.Leg(tll, "2026-10-24T07:30+03:00", "2026-10-24T04:30Z", "2026-10-24T07:42+03:00", null, "1"),
                new FlightSchedule.Leg(ams, "2026-10-24T09:00+02:00", "2026-10-24T07:00Z", null, "2026-10-24T08:56+02:00", "2"),
                "2026-06-02T08:13Z");
    }

    static FlightLive live() {
        return new FlightLive("active",
                new FlightLive.Leg("8", null, 12, "2026-10-24T07:42:00"),
                new FlightLive.Leg("A4", "15", 0, "2026-10-24T08:57:00"));
    }

    @Test
    void everyFieldComesBackAsItWasSaved() {
        String number = number();
        FlightRecord full = new FlightRecord(number, DAY, schedule(), live(),
                Instant.parse("2026-10-22T01:00:04.211Z"), Instant.parse("2026-10-24T05:12:40.870Z"), false,
                java.util.List.of(schedule()));

        repository().save(full);

        assertThat(repository().find(number, DAY)).get().usingRecursiveComparison().isEqualTo(full);
    }

    @Test
    void aZeroMinuteDelayIsAnAnswerNotAnAbsence() {
        String number = number();
        repository().save(new FlightRecord(number, DAY, null, live(), null, Instant.parse("2026-10-24T05:12:40Z"), false));

        assertThat(repository().find(number, DAY).orElseThrow().live().arrival().delayMinutes()).isEqualTo(0);
    }

    @Test
    void aStoredMissRoundTrips() {
        String number = number();
        FlightRecord miss = FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:07.020Z"));

        repository().save(miss);

        FlightRecord loaded = repository().find(number, DAY).orElseThrow();
        assertThat(loaded.notFound()).isTrue();
        assertThat(loaded.schedule()).isNull();
        assertThat(loaded.scheduleFetchedAt()).isEqualTo(miss.scheduleFetchedAt());
    }

    @Test
    void savingTwiceReplacesTheRowForThatFlightAndDate() {
        String number = number();
        repository().save(FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:00Z")));
        repository().save(new FlightRecord(number, DAY, schedule(), null,
                Instant.parse("2026-10-24T02:00:00Z"), null, false));

        assertThat(repository().findAll().stream().filter(r -> r.flightNumber().equals(number))).hasSize(1);
        assertThat(repository().find(number, DAY).orElseThrow().notFound()).isFalse();
    }

    @Test
    void theSameFlightOnAnotherDateIsAnotherRow() {
        String number = number();
        repository().save(FlightRecord.missing(number, DAY, Instant.parse("2026-10-24T01:00:00Z")));

        assertThat(repository().find(number, DAY.plusDays(1))).isEmpty();
    }
}
