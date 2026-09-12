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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class ItineraryRepository extends TripScopedYamlRepository<ItineraryItem> {

    public ItineraryRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<ItineraryItem>>() {}, ItineraryItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.itinerary(tripSlug);
    }

    /** Chronological, with undated entries last. */
    public List<ItineraryItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator
                        .comparing(ItineraryItem::getStartAt,
                                Comparator.<LocalDateTime>nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(ItineraryItem::getSortOrder))
                .toList();
    }

    public List<ItineraryItem> findByChecklistItem(String tripSlug, String checklistItemId) {
        return findAllOrdered(tripSlug).stream()
                .filter(item -> checklistItemId.equals(item.getChecklistItemId()))
                .toList();
    }
}
