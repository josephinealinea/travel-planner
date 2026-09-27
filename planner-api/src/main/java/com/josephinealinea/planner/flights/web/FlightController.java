package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.api.FlightLookup;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.trips.api.TripAccessService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The form's lookup icon. Members only: a lookup spends the install's free-tier
 * quota. Read-only, and it never writes to the itinerary: the form fills its own
 * fields and the member saves them.
 */
@RestController
@RequestMapping("/api/v1/trips/{tripId}/flights")
public class FlightController {

    /**
     * {@code status} is {@code found}, {@code notFound} or {@code unavailable}.
     * Times are airport wall-clock (HH:mm), and the arrival has its own date
     * because an overnight or date-line flight lands on another day.
     *
     * <p>When one number flies several legs that day, the times and {@code flight}
     * at the top are null and {@code choices} lists every leg, each shaped like
     * the single answer; the member picks theirs. With one leg {@code choices} is
     * empty and the top level is filled, exactly as before.
     */
    public record LookupResponse(String status, String operatingNumber, String departureTime,
                                 String arrivalDate, String arrivalTime, FlightSnapshot flight,
                                 List<Choice> choices) {}

    public record Choice(String departureTime, String arrivalDate, String arrivalTime, FlightSnapshot flight) {}

    private final FlightLookup lookup;
    private final TripAccessService access;
    private final CurrentUserContext currentUser;

    public FlightController(FlightLookup lookup, TripAccessService access, CurrentUserContext currentUser) {
        this.lookup = lookup;
        this.access = access;
        this.currentUser = currentUser;
    }

    @GetMapping("/lookup")
    LookupResponse lookup(@PathVariable String tripId,
                          @RequestParam String number,
                          @RequestParam LocalDate date) {
        access.requireMember(tripId, currentUser.userId());
        FlightLookup.Outcome outcome = lookup.lookup(number, date);

        if (outcome.status() != Fetch.Status.FOUND) {
            String status = outcome.status() == Fetch.Status.NOT_FOUND ? "notFound" : "unavailable";
            return new LookupResponse(status, null, null, null, null, null, List.of());
        }
        if (outcome.legs().size() > 1) {
            List<Choice> choices = outcome.legs().stream()
                    .map(leg -> choice(number, outcome.operatingNumber(), leg)).toList();
            return new LookupResponse("found", outcome.operatingNumber(), null, null, null, null, choices);
        }
        Choice only = choice(number, outcome.operatingNumber(), outcome.schedule());
        return new LookupResponse("found", outcome.operatingNumber(), only.departureTime(),
                only.arrivalDate(), only.arrivalTime(), only.flight(), List.of());
    }

    private static Choice choice(String booked, String operating, FlightSchedule schedule) {
        LocalDateTime departs = localOf(schedule.departure().scheduledLocal());
        LocalDateTime arrives = localOf(schedule.arrival().scheduledLocal());
        return new Choice(departs == null ? null : departs.toLocalTime().toString(),
                arrives == null ? null : arrives.toLocalDate().toString(),
                arrives == null ? null : arrives.toLocalTime().toString(),
                FlightLookup.snapshotOf(booked, operating, schedule));
    }

    /** The wall-clock part of {@code 2026-10-24T07:30+03:00}, dropping the offset. */
    private static LocalDateTime localOf(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso).toLocalDateTime();
    }
}
