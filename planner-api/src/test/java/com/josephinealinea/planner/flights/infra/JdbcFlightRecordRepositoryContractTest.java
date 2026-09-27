package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

@PostgresTest
class JdbcFlightRecordRepositoryContractTest extends FlightRecordRepositoryContract {
    private final JdbcFlightRecordRepository repository = new JdbcFlightRecordRepository(PostgresTestDatabase.jdbc());
    @Override protected FlightRecordRepository repository() { return repository; }
}
