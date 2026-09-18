package com.josephinealinea.planner.identity.infra;

import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The user contract against PostgreSQL — the proof that
 * {@link JdbcUserRepository} behaves as {@code users.yml} does — plus the one
 * place the two deliberately differ.
 */
@PostgresTest
class JdbcUserRepositoryContractTest extends UserRepositoryContract {

    private final JdbcUserRepository repository =
            new JdbcUserRepository(PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected UserRepository repository() {
        return repository;
    }

    /**
     * {@code users.email} is UNIQUE. YAML would take the duplicate and let
     * findByEmail pick the first; the app never writes one, so here it fails
     * loudly instead of lurking.
     */
    @Test
    void twoUsersCannotShareAnEmail() {
        repository.save(user("u-1", "ana@example.com"));

        assertThatThrownBy(() -> repository.save(user("u-2", "ANA@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findAll()).hasSize(1);
    }

    /** A null currency list is stored as the column's own empty-array default. */
    @Test
    void aNullCurrencyListIsStoredAsAnEmptyArray() {
        User user = user("u-1", "ana@example.com");
        user.setCurrencies(null);

        repository.save(user);

        assertThat(PostgresTestDatabase.jdbc()
                .sql("SELECT cardinality(currencies) FROM users WHERE id = 'u-1'")
                .query(Integer.class).single()).isZero();
    }
}
