package com.josephinealinea.planner.checklist;

import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.JdbcChecklistRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

/** The checklist contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcChecklistRepositoryTest extends ChecklistRepositoryContract {

    private final JdbcChecklistRepository repository = new JdbcChecklistRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected ChecklistRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip(tripIdOf(slug), slug);
    }
}
