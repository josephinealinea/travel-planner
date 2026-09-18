package com.josephinealinea.planner.identity.infra;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The user contract against {@code users.yml} in a temp directory. Always runs. */
class YamlUserRepositoryContractTest extends UserRepositoryContract {

    private YamlUserRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        repository = new YamlUserRepository(new YamlStore(), new YamlPaths(props), new TripLocks());
    }

    @Override
    protected UserRepository repository() {
        return repository;
    }
}
