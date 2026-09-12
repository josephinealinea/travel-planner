package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.stereotype.Service;

/**
 * The single place every permission decision is made.
 *
 * All members have equal rights over a trip's contents; only the owner can
 * delete the whole trip or publish it. Non-members are told "not found" rather
 * than "forbidden", so trip ids cannot be probed.
 */
@Service
public class TripAccessService {

    private final TripRepository trips;

    public TripAccessService(TripRepository trips) {
        this.trips = trips;
    }

    /** Any member: read and edit destinations, checklist, itinerary, budget, members. */
    public Trip requireMember(String tripId, String userId) {
        Trip trip = trips.findById(tripId).orElseThrow(() -> ApiException.notFound("Trip"));
        if (!trip.isMember(userId)) throw ApiException.notFound("Trip");
        return trip;
    }

    /** Owner only: deleting the trip, publishing it, deciding publish requests. */
    public Trip requireOwner(String tripId, String userId) {
        Trip trip = requireMember(tripId, userId);
        if (!trip.isOwner(userId)) {
            throw ApiException.forbidden("Only the trip owner can do that.");
        }
        return trip;
    }
}
