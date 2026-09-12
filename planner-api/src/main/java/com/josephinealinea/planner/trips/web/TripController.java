package com.josephinealinea.planner.trips.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.api.TripViews;
import com.josephinealinea.planner.trips.domain.Trip;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    private final TripService trips;
    private final TripAccessService access;
    private final TripViewAssembler views;
    private final CurrentUserContext currentUser;

    public TripController(TripService trips,
                          TripAccessService access,
                          TripViewAssembler views,
                          CurrentUserContext currentUser) {
        this.trips = trips;
        this.access = access;
        this.views = views;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<TripViews.TripSummary> list() {
        String userId = currentUser.userId();
        return trips.listFor(userId).stream()
                .map(trip -> views.summary(trip, userId))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TripViews.TripDetail create(@Valid @RequestBody TripDtos.CreateTripRequest request) {
        String userId = currentUser.userId();
        Trip trip = trips.create(userId, request.title(), request.startDate(), request.endDate());
        return views.detail(trip, userId);
    }

    /** The whole workspace in one request — every tab reads from this. */
    @GetMapping("/{tripId}")
    TripViews.TripDetail detail(@PathVariable String tripId) {
        String userId = currentUser.userId();
        return views.detail(access.requireMember(tripId, userId), userId);
    }

    @PatchMapping("/{tripId}")
    TripViews.TripDetail update(@PathVariable String tripId,
                                @Valid @RequestBody TripDtos.UpdateTripRequest request) {
        String userId = currentUser.userId();
        Trip trip = trips.update(tripId, userId, request.title(), request.startDate(),
                request.endDate(), request.displayCurrency(), request.exchangeRates());
        return views.detail(trip, userId);
    }

    /** Owner only. */
    @DeleteMapping("/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId) {
        trips.delete(tripId, currentUser.userId());
    }

    // ── members ─────────────────────────────────────────

    @GetMapping("/{tripId}/members")
    List<TripViews.MemberView> members(@PathVariable String tripId) {
        return views.members(access.requireMember(tripId, currentUser.userId()));
    }

    /** Any member can add another; a new email also gets an account and an email. */
    @PostMapping("/{tripId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    List<TripViews.MemberView> addMember(@PathVariable String tripId,
                                         @Valid @RequestBody TripDtos.AddMemberRequest request) {
        return views.members(trips.addMember(tripId, currentUser.userId(), request.email()));
    }

    /** Any member can remove another; removing yourself leaves the trip. */
    @DeleteMapping("/{tripId}/members/{userId}")
    List<TripViews.MemberView> removeMember(@PathVariable String tripId, @PathVariable String userId) {
        return views.members(trips.removeMember(tripId, currentUser.userId(), userId));
    }
}
