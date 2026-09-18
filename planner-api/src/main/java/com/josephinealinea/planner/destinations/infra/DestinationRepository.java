package com.josephinealinea.planner.destinations.infra;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.storage.TripScopedRepository;

import java.util.Comparator;
import java.util.List;

/**
 * The storage contract for destinations: {@link YamlDestinationRepository}
 * with the database flag off, a JDBC implementation with it on. Services
 * depend on this interface only.
 *
 * {@link #findAllOrdered} is a default method so its ordering is written once
 * for both stores — see BudgetRepository for why.
 */
public interface DestinationRepository extends TripScopedRepository<Destination> {

    /** Route order: by start date where known, then by explicit sort order. */
    default List<Destination> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator
                        .comparing(Destination::getStartDate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparingInt(Destination::getSortOrder))
                .toList();
    }
}
