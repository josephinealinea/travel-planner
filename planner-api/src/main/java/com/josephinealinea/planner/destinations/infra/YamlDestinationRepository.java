package com.josephinealinea.planner.destinations.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/** Destinations as one YAML list per trip: travels/destinations/&lt;slug&gt;.yml. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlDestinationRepository extends TripScopedYamlRepository<Destination> implements DestinationRepository {

    public YamlDestinationRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<Destination>>() {}, Destination::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.destinations(tripSlug);
    }
}
