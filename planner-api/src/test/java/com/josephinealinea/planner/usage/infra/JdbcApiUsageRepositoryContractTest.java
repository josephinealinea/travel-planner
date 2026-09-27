package com.josephinealinea.planner.usage.infra;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

/** The usage contract against PostgreSQL: {@code api_usage}. */
@PostgresTest
class JdbcApiUsageRepositoryContractTest extends ApiUsageRepositoryContract {

    private final JdbcApiUsageRepository repository = new JdbcApiUsageRepository(PostgresTestDatabase.jdbc());

    @Override
    protected ApiUsageRepository repository() {
        return repository;
    }
}
