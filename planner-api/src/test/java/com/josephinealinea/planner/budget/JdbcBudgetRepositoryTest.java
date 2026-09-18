package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.JdbcBudgetRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The budget contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcBudgetRepositoryTest extends BudgetRepositoryContract {

    private final JdbcBudgetRepository repository = new JdbcBudgetRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected BudgetRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip(tripIdOf(slug), slug);
    }

    /**
     * The table-side twin of BudgetStatusTest's hand-written YAML file: a row
     * inserted with no status at all — by the import, or by hand in a SQL
     * console — reads as a charge, not as pending.
     */
    @Test
    void aRowInsertedWithoutAStatusReadsAsACharge() {
        PostgresTestDatabase.jdbc()
                .sql("INSERT INTO budget_items (trip_id, id, description) VALUES (:trip, 'raw', 'Hand-inserted')")
                .param("trip", tripIdOf(TRIP))
                .update();

        var loaded = store().findById(TRIP, "raw").orElseThrow();
        assertThat(loaded.isConfirmed()).isTrue();
        assertThat(loaded.getSharedByUserIds()).isEmpty();
        assertThat(loaded.getCountryCodes()).isEmpty();
    }
}
