package com.josephinealinea.planner.trips.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Trip content lives in one file per trip (travels/trip/&lt;slug&gt;.yml) and is the
 * single source of truth. trips/index.yml is only a directory of id-to-slug
 * pairs, so there is no duplicated trip data to fall out of sync.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlTripRepository implements TripRepository {

    /** The id-to-slug directory entry. */
    public record IndexEntry(String id, String slug) {}

    private static final TypeReference<List<IndexEntry>> INDEX = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;
    private final TripLocks locks;

    public YamlTripRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        this.store = store;
        this.paths = paths;
        this.locks = locks;
    }

    @Override
    public List<Trip> findAllForUser(String userId) {
        return index().stream()
                .map(entry -> loadBySlug(entry.slug()))
                .flatMap(Optional::stream)
                .filter(trip -> trip.isMember(userId))
                .sorted(Comparator.comparing(Trip::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Override
    public Optional<Trip> findById(String tripId) {
        return index().stream()
                .filter(entry -> entry.id().equals(tripId))
                .findFirst()
                .flatMap(entry -> loadBySlug(entry.slug()));
    }

    @Override
    public Optional<Trip> findBySlug(String slug) {
        return loadBySlug(slug);
    }

    @Override
    public boolean slugExists(String slug) {
        return index().stream().anyMatch(entry -> entry.slug().equals(slug));
    }

    @Override
    public Trip save(Trip trip) {
        trip.setUpdatedAt(Instant.now());
        if (trip.getCreatedAt() == null) trip.setCreatedAt(Instant.now());

        locks.writeVoid(trip.getSlug(), () -> store.write(paths.trip(trip.getSlug()), trip));

        locks.writeVoid(TripLocks.TRIP_INDEX, () -> {
            List<IndexEntry> entries = new ArrayList<>(index());
            boolean known = entries.stream().anyMatch(e -> e.id().equals(trip.getId()));
            if (!known) {
                entries.add(new IndexEntry(trip.getId(), trip.getSlug()));
                store.write(paths.tripIndex(), entries);
            }
        });
        return trip;
    }

    @Override
    public void delete(Trip trip) {
        String slug = trip.getSlug();
        locks.writeVoid(slug, () -> {
            store.delete(paths.trip(slug));
            store.delete(paths.destinations(slug));
            store.delete(paths.checklist(slug));
            store.delete(paths.itinerary(slug));
            store.delete(paths.budget(slug));
        });
        locks.writeVoid(TripLocks.TRIP_INDEX, () -> {
            List<IndexEntry> remaining = index().stream()
                    .filter(entry -> !entry.id().equals(trip.getId()))
                    .toList();
            store.write(paths.tripIndex(), remaining);
        });
    }

    private List<IndexEntry> index() {
        return locks.read(TripLocks.TRIP_INDEX, () -> store.readList(paths.tripIndex(), INDEX));
    }

    private Optional<Trip> loadBySlug(String slug) {
        return locks.read(slug, () ->
                Optional.ofNullable(store.read(paths.trip(slug), Trip.class, null)));
    }
}
