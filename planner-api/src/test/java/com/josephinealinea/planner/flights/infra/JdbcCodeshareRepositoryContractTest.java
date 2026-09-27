package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

@PostgresTest
class JdbcCodeshareRepositoryContractTest extends CodeshareRepositoryContract {
    private final JdbcCodeshareRepository repository = new JdbcCodeshareRepository(PostgresTestDatabase.jdbc());
    @Override protected CodeshareRepository repository() { return repository; }
}
