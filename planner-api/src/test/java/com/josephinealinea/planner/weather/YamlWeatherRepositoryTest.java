package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.storage.TestYamlPaths;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import com.josephinealinea.planner.weather.infra.YamlWeatherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The weather contract against YAML. */
class YamlWeatherRepositoryTest extends WeatherRepositoryContract {

    private YamlWeatherRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlWeatherRepository(new YamlStore(), TestYamlPaths.under(dir), new TripLocks());
    }

    @Override
    protected WeatherRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
