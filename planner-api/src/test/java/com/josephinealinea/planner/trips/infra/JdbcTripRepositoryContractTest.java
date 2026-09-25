package com.josephinealinea.planner.trips.infra;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trip contract against PostgreSQL — the proof that
 * {@link JdbcTripRepository} behaves as the YAML trip files do — plus the
 * places the schema itself carries a rule.
 */
@PostgresTest
class JdbcTripRepositoryContractTest extends TripRepositoryContract {

    /**
     * Every table deleting a trip must clear, with one row's worth of SQL for
     * each. {@link #everyTableReferencingTripsIsCovered} fails when a table is
     * added that references {@code trips} without being listed here — so a
     * sixth per-trip entity cannot slip past the delete test.
     */
    private static final Map<String, String> PER_TRIP_ROWS = Map.of(
            "trip_members", """
                    INSERT INTO trip_members (trip_id, user_id, position)
                    VALUES (:tripId, 'extra-member', 99)""",
            "publish_requests", """
                    INSERT INTO publish_requests (trip_id, id, position) VALUES (:tripId, 'r-extra', 99)""",
            "destinations", """
                    INSERT INTO destinations (trip_id, id, name) VALUES (:tripId, 'd-1', 'Cusco')""",
            "checklist_items", """
                    INSERT INTO checklist_items (trip_id, id, description) VALUES (:tripId, 'c-1', 'Book')""",
            "itinerary_items", """
                    INSERT INTO itinerary_items (trip_id, id, description) VALUES (:tripId, 'i-1', 'Train')""",
            "budget_items", """
                    INSERT INTO budget_items (trip_id, id, amount) VALUES (:tripId, 'b-1', 12.50)""",
            "settlement_payments", """
                    INSERT INTO settlement_payments (trip_id, id, amount) VALUES (:tripId, 'p-1', 20.00)""",
            "weather_records", """
                    INSERT INTO weather_records (trip_id, id, latitude, longitude, date)
                    VALUES (:tripId, '-13.5,-72.0:2026-10-25', -13.5, -72.0, DATE '2026-10-25')""");

    private final JdbcTripRepository repository =
            new JdbcTripRepository(PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    private static JdbcClient jdbc() {
        return PostgresTestDatabase.jdbc();
    }

    @Override
    protected TripRepository repository() {
        return repository;
    }

    @Override
    protected void givenUser(String userId) {
        jdbc().sql("INSERT INTO users (id, email) VALUES (:id, :email) ON CONFLICT DO NOTHING")
                .param("id", userId)
                .param("email", userId + "@example.com")
                .update();
    }

    @Override
    protected void givenDataInEveryPerTripStore(Trip trip) {
        givenUser("extra-member");
        PER_TRIP_ROWS.values().forEach(sql -> jdbc().sql(sql).param("tripId", trip.getId()).update());
    }

    @Override
    protected long perTripDataRemaining(Trip trip) {
        long total = 0;
        for (String table : PER_TRIP_ROWS.keySet()) {
            total += jdbc().sql("SELECT count(*) FROM " + table + " WHERE trip_id = :tripId")
                    .param("tripId", trip.getId())
                    .query(Long.class)
                    .single();
        }
        return total;
    }

    /** The database does not keep {@code Trip.exchangeRates}; see JdbcTripRepository. */
    @Override
    protected Set<String> unstoredFields() {
        return Set.of("exchangeRates");
    }

    // ── what only the database has to prove ─────

    /**
     * The one intentional difference from YAML: the legacy per-trip rates are
     * not stored. Nothing reads them — rates are one table for the install —
     * and a database has no old files that need them to load.
     */
    @Test
    void exchangeRatesAreNotStoredAndReadBackEmpty() {
        givenUser("owner");
        Trip trip = trip("t-1", "latam", "owner");
        trip.getExchangeRates().put("PEN", new BigDecimal("3.89"));

        repository.save(trip);

        assertThat(repository.findById("t-1").orElseThrow().getExchangeRates()).isEmpty();
    }

    @Test
    void everyTableReferencingTripsIsCovered() {
        List<String> referencing = jdbc().sql("""
                        SELECT DISTINCT cl.relname
                        FROM pg_constraint c
                        JOIN pg_class cl ON cl.oid = c.conrelid
                        WHERE c.contype = 'f' AND c.confrelid = 'trips'::regclass
                          -- Scratch tables other tests create in the shared
                          -- container (storage/jdbc's test_notes) are not schema.
                          AND cl.relname NOT LIKE 'test\\_%'
                        """)
                .query(String.class)
                .list();

        assertThat(referencing).containsExactlyInAnyOrderElementsOf(PER_TRIP_ROWS.keySet());
    }

    /** Member order is stored as a position, not left to the database's whim. */
    @Test
    void memberPositionIsTheListIndex() {
        for (String id : List.of("owner", "b", "c")) givenUser(id);
        Trip trip = trip("t-1", "latam", "owner");
        trip.getMembers().add(member("c", TripRole.MEMBER));
        trip.getMembers().add(member("b", TripRole.MEMBER));

        repository.save(trip);

        assertThat(jdbc().sql("SELECT user_id FROM trip_members WHERE trip_id = 't-1' ORDER BY position")
                .query(String.class).list())
                .containsExactly("owner", "c", "b");
        assertThat(jdbc().sql("SELECT position FROM trip_members WHERE trip_id = 't-1' ORDER BY position")
                .query(Integer.class).list())
                .containsExactly(0, 1, 2);
    }

    /** A failure part-way through a save leaves the trip as it was: one transaction. */
    @Test
    void aSaveThatFailsPartWayChangesNothing() {
        givenUser("owner");
        Trip trip = trip("t-1", "latam", "owner");
        trip.setTitle("Before");
        repository.save(trip);

        Trip broken = repository.findById("t-1").orElseThrow();
        broken.setTitle("After");
        // A member whose account does not exist violates the users FK — after
        // the trip row has been updated and the old members deleted.
        broken.getMembers().add(member("no-such-user", TripRole.MEMBER));

        assertThatThrownBy(() -> repository.save(broken))
                .isInstanceOf(DataIntegrityViolationException.class);

        Trip read = repository.findById("t-1").orElseThrow();
        assertThat(read.getTitle()).isEqualTo("Before");
        assertThat(read.getMembers()).extracting(TripMember::getUserId).containsExactly("owner");
    }
}
