package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.infra.JdbcSettlementPaymentRepository;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;

/** The settlement-payment contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcSettlementPaymentRepositoryTest extends SettlementPaymentRepositoryContract {

    private final JdbcSettlementPaymentRepository repository = new JdbcSettlementPaymentRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected SettlementPaymentRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip(tripIdOf(slug), slug);
    }
}
