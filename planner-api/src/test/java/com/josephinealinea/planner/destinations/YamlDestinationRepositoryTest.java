package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * The destination contract against YAML. (YamlDestinationRepositoryContractTest
 * predates it and runs only the generic half; this runs both.)
 */
class YamlDestinationRepositoryTest extends DestinationRepositoryContract {

    private YamlDestinationRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlDestinationRepository(new YamlStore(), PerTripStores.yamlPaths(dir), new TripLocks());
    }

    @Override
    protected DestinationRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
