package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.JdbcDestinationRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

/** The destination contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcDestinationRepositoryTest extends DestinationRepositoryContract {

    private final JdbcDestinationRepository repository = new JdbcDestinationRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected DestinationRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip(tripIdOf(slug), slug);
    }
}
