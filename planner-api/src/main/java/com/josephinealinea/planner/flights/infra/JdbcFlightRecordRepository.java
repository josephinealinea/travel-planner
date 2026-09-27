// JdbcFlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@code flight_records}. {@code schedule} and {@code live} are jsonb documents
 * (bound as text and cast in the statement, like {@code weather_records.details}).
 * {@code not_found} is NOT NULL with a default, and an explicit NULL would not
 * take that default, so it is always written as a real boolean.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcFlightRecordRepository implements FlightRecordRepository {

    private final JdbcClient jdbc;

    public JdbcFlightRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FlightRecord> find(String flightNumber, LocalDate departureDate) {
        return jdbc.sql("SELECT * FROM flight_records WHERE flight_number = :n AND departure_date = :d")
                .param("n", flightNumber)
                .param("d", departureDate)
                .query((rs, i) -> map(rs))
                .optional();
    }

    @Override
    public void save(FlightRecord record) {
        jdbc.sql("""
                        INSERT INTO flight_records
                            (flight_number, departure_date, schedule, live,
                             schedule_fetched_at, live_fetched_at, not_found, other_legs)
                        VALUES (:n, :d, :schedule::jsonb, :live::jsonb, :sf, :lf, :nf, :legs::jsonb)
                        ON CONFLICT (flight_number, departure_date) DO UPDATE SET
                            schedule = EXCLUDED.schedule, live = EXCLUDED.live,
                            schedule_fetched_at = EXCLUDED.schedule_fetched_at,
                            live_fetched_at = EXCLUDED.live_fetched_at,
                            not_found = EXCLUDED.not_found, other_legs = EXCLUDED.other_legs
                        """)
                .param("n", record.flightNumber())
                .param("d", record.departureDate())
                .param("schedule", JdbcValues.json(record.schedule()))
                .param("live", JdbcValues.json(record.live()))
                .param("sf", JdbcValues.timestamptz(record.scheduleFetchedAt()))
                .param("lf", JdbcValues.timestamptz(record.liveFetchedAt()))
                .param("nf", record.notFound())
                .param("legs", JdbcValues.json(record.otherLegs()))
                .update();
    }

    @Override
    public List<FlightRecord> findAll() {
        return jdbc.sql("SELECT * FROM flight_records ORDER BY flight_number, departure_date")
                .query((rs, i) -> map(rs))
                .list();
    }

    private static FlightRecord map(ResultSet rs) throws SQLException {
        return new FlightRecord(
                rs.getString("flight_number"),
                JdbcValues.localDate(rs, "departure_date"),
                JdbcValues.json(rs, "schedule", FlightSchedule.class),
                JdbcValues.json(rs, "live", FlightLive.class),
                JdbcValues.instant(rs, "schedule_fetched_at"),
                JdbcValues.instant(rs, "live_fetched_at"),
                rs.getBoolean("not_found"),
                legs(JdbcValues.json(rs, "other_legs", FlightSchedule[].class)));
    }

    private static List<FlightSchedule> legs(FlightSchedule[] read) {
        return read == null ? null : List.of(read);
    }
}
