package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.i18n.Msg;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.trips.domain.Trip;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The trip's own dates, as the range every date recorded against it has to
 * fall inside.
 *
 * A destination, plan or expense dated outside the trip is nearly always a
 * mis-picked month or year, and nothing downstream notices: the itinerary
 * silently grows a day months away from the others, and the published page
 * renders it. Catching it at the service is what keeps that out of the YAML.
 *
 * Only values the caller actually sends are checked. Moving a trip's own dates
 * therefore never makes its existing rows uneditable — they stay as they are
 * until somebody edits that date itself.
 */
public record TripWindow(LocalDate start, LocalDate end) {

    public static TripWindow of(Trip trip) {
        return new TripWindow(trip.getStartDate(), trip.getEndDate());
    }

    /**
     * Rejects a date outside the trip. Both ends are inclusive — a flight on
     * the first day and a checkout on the last are the normal case.
     *
     * @param whatKey message key naming the field back to the caller, e.g.
     *                {@code field.startDate}
     */
    public void require(LocalDate date, String whatKey) {
        if (date == null || start == null || end == null) return;
        if (date.isBefore(start) || date.isAfter(end)) {
            throw ApiException.badRequest(
                    "error.dates.outsideTrip", new Msg(whatKey), start, end);
        }
    }

    /** The itinerary stores times; only the day part has to be inside the trip. */
    public void require(LocalDateTime at, String whatKey) {
        require(at == null ? null : at.toLocalDate(), whatKey);
    }
}
