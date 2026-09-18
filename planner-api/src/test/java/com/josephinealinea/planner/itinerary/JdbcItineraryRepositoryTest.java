package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.JdbcItineraryRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/** The itinerary contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcItineraryRepositoryTest extends ItineraryRepositoryContract {

    private final JdbcItineraryRepository repository = new JdbcItineraryRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected ItineraryRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip(tripIdOf(slug), slug);
    }

    /**
     * The trap a {@code timestamptz} would set: written by a server in
     * Singapore, read by one in Lima, a 06:00 departure must still be 06:00 —
     * in the column itself and in what comes back. PgJDBC opens each
     * connection in the JVM's default zone, so switching it between the write
     * and the read is exactly the case (and a fresh connection per statement
     * picks the change up).
     */
    @Test
    void wallClockTimesIgnoreEveryTimeZoneInvolved() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"));
            ItineraryItem flight = entity("flight", "LA2025 Lima to Cusco");
            flight.setStartAt(LocalDateTime.of(2026, 10, 25, 6, 0));
            store().save(TRIP, flight);

            TimeZone.setDefault(TimeZone.getTimeZone("America/Lima"));
            assertThat(store().findById(TRIP, "flight").orElseThrow().getStartAt())
                    .isEqualTo(LocalDateTime.of(2026, 10, 25, 6, 0));
            assertThat(PostgresTestDatabase.jdbc()
                    .sql("SELECT to_char(start_at, 'YYYY-MM-DD HH24:MI') FROM itinerary_items WHERE id = 'flight'")
                    .query(String.class).single())
                    .isEqualTo("2026-10-25 06:00");
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
