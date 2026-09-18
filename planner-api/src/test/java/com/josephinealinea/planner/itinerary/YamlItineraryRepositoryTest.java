package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.destinations.PerTripStores;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The itinerary contract against YAML. */
class YamlItineraryRepositoryTest extends ItineraryRepositoryContract {

    private YamlItineraryRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlItineraryRepository(new YamlStore(), PerTripStores.yamlPaths(dir), new TripLocks());
    }

    @Override
    protected ItineraryRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
