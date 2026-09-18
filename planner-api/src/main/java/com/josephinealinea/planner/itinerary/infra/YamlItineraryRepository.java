package com.josephinealinea.planner.itinerary.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/** Itinerary entries as one YAML list per trip: travels/itinerary/&lt;slug&gt;.yml. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlItineraryRepository extends TripScopedYamlRepository<ItineraryItem> implements ItineraryRepository {

    public YamlItineraryRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<ItineraryItem>>() {}, ItineraryItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.itinerary(tripSlug);
    }
}
