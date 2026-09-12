package com.josephinealinea.planner.trips.infra;

import com.josephinealinea.planner.trips.domain.Trip;

import java.util.List;
import java.util.Optional;

public interface TripRepository {

    /** Trips the user owns or is a member of. */
    List<Trip> findAllForUser(String userId);

    Optional<Trip> findById(String tripId);

    Optional<Trip> findBySlug(String slug);

    boolean slugExists(String slug);

    Trip save(Trip trip);

    /** Removes the trip and every file belonging to it. */
    void delete(Trip trip);
}
