package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import com.josephinealinea.planner.weather.infra.JdbcWeatherRepository;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The weather contract against PostgreSQL — the same assertions YAML passes. */
@PostgresTest
class JdbcWeatherRepositoryTest extends WeatherRepositoryContract {

    private final JdbcWeatherRepository repository = new JdbcWeatherRepository(
            PostgresTestDatabase.jdbc(), PostgresTestDatabase.transactionManager());

    @Override
    protected WeatherRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        PostgresTestDatabase.givenTrip("trip-" + slug, slug);
    }

    /**
     * The one deliberate difference from YAML: a record's date is part of its
     * key, so the table refuses a record without one where a file would take
     * it. WeatherService never writes such a record; this pins that the
     * refusal is loud and leaves nothing behind.
     */
    @Test
    void aRecordWithNoDateIsRefused() {
        WeatherRecord undated = new WeatherRecord();
        undated.setId("no-date");

        assertThatThrownBy(() -> store().save(TRIP, undated))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store().findAll(TRIP)).isEmpty();
    }
}
