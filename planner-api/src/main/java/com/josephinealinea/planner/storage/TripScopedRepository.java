package com.josephinealinea.planner.storage;

import java.util.List;
import java.util.Optional;

/**
 * The storage contract shared by every per-trip list — destinations, checklist
 * items, itinerary entries, budget rows and weather readings.
 *
 * Two implementations hold it: {@link TripScopedYamlRepository} (one YAML file
 * per trip) and {@code storage.jdbc.TripScopedJdbcRepository} (one table,
 * keyed by trip). What both must honour, because services rely on it:
 *
 * <ul>
 *   <li><b>A list has an order, and it is insertion order.</b> {@link #findAll}
 *       returns rows in the order they were first saved; budget and weather
 *       have no sort field of their own and tie-break on exactly this.</li>
 *   <li><b>{@link #save} replaces in place.</b> Saving an existing id keeps its
 *       position; only a new id is appended.</li>
 *   <li><b>{@link #replaceAll} makes the list exactly the given one</b> — rows
 *       absent from it are gone, and the given order becomes the stored
 *       order. The cascades use it so they cannot half-apply.</li>
 *   <li><b>Everything is scoped by trip slug.</b> Ids are only unique within a
 *       trip (weather ids repeat across trips by design), so a lookup never
 *       sees another trip's rows.</li>
 * </ul>
 *
 * {@code TripScopedRepositoryContract} in the tests pins every one of these
 * against each implementation.
 */
public interface TripScopedRepository<T> {

    /** Every row of the trip, in insertion order. Empty — never null — for an unknown trip. */
    List<T> findAll(String tripSlug);

    Optional<T> findById(String tripSlug, String id);

    /** Inserts or replaces by id, preserving list order. */
    T save(String tripSlug, T entity);

    /** One write for a batch — used when seeding a destination's three checklist items. */
    List<T> saveAll(String tripSlug, List<T> entities);

    void delete(String tripSlug, String id);

    /**
     * Replaces the whole list in one write. Used for reordering and for the
     * cascades that touch several rows at once, so they cannot half-apply.
     */
    void replaceAll(String tripSlug, List<T> items);
}
