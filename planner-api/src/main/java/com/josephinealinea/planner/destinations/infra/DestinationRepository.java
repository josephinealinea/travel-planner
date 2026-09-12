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
import java.util.Comparator;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class DestinationRepository extends TripScopedYamlRepository<Destination> {

    public DestinationRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<Destination>>() {}, Destination::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.destinations(tripSlug);
    }

    /** Route order: by start date where known, then by explicit sort order. */
    public List<Destination> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator
                        .comparing(Destination::getStartDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(Destination::getSortOrder))
                .toList();
    }
}
