package com.josephinealinea.planner.rates.infra;

import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The rates contract against PostgreSQL: {@code rate_table} plus {@code exchange_rates}. */
@PostgresTest
class JdbcRatesRepositoryContractTest extends RatesRepositoryContract {

    private final JdbcRatesRepository repository =
            new JdbcRatesRepository(PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected RatesRepository repository() {
        return repository;
    }

    /** One table for the install: saving twice leaves one row, not two. */
    @Test
    void thereIsOnlyEverOneTableRow() {
        repository.save(table(List.of("EUR", "USD")));
        repository.save(table(List.of("EUR", "PEN")));

        assertThat(PostgresTestDatabase.jdbc().sql("SELECT count(*) FROM rate_table")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(PostgresTestDatabase.jdbc().sql("SELECT count(*) FROM exchange_rates")
                .query(Long.class).single()).isEqualTo(2);
    }

    /** Saving an empty table — which the app never does — reads back as empty. */
    @Test
    void anEmptyTableRoundTripsAsEmpty() {
        repository.save(table(List.of("EUR", "USD")));
        repository.save(new RateTable());

        assertThat(repository.load().isEmpty()).isTrue();
    }
}
