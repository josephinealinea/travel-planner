package com.josephinealinea.planner.itinerary.infra;

import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.storage.TripScopedRepository;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * The storage contract for itinerary entries: {@link YamlItineraryRepository}
 * with the database flag off, a JDBC implementation with it on. Services
 * depend on this interface only.
 *
 * The finders are default methods so their ordering is written once for both
 * stores — see BudgetRepository for why.
 */
public interface ItineraryRepository extends TripScopedRepository<ItineraryItem> {

    /** Chronological, with undated entries last. */
    default List<ItineraryItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator
                        .comparing(ItineraryItem::getStartAt,
                                Comparator.<LocalDateTime>nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(ItineraryItem::getSortOrder))
                .toList();
    }

    default List<ItineraryItem> findByChecklistItem(String tripSlug, String checklistItemId) {
        return findAllOrdered(tripSlug).stream()
                .filter(item -> checklistItemId.equals(item.getChecklistItemId()))
                .toList();
    }
}
