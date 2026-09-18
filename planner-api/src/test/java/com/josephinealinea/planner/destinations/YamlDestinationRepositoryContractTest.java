package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The per-trip contract against the YAML store, through destinations. The
 * behaviour lives in TripScopedYamlRepository, so one module proves it for
 * all five; a PostgreSQL subclass per module runs the same assertions.
 */
class YamlDestinationRepositoryContractTest extends TripScopedRepositoryContract<Destination> {

    private YamlDestinationRepository repository;

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
        repository = new YamlDestinationRepository(new YamlStore(), new YamlPaths(props), new TripLocks());
    }

    @Override
    protected TripScopedRepository<Destination> repository() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }

    @Override
    protected Destination entity(String id, String label) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setName(label);
        return destination;
    }

    @Override
    protected String idOf(Destination entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(Destination entity) {
        return entity.getName();
    }
}
