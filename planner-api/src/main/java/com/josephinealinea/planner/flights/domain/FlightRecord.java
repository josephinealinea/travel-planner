package com.josephinealinea.planner.flights.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One row of the install-wide flight cache, keyed by operating flight number
 * and the local departure date. The two halves are fetched separately, so each
 * has its own fetch time.
 *
 * <p>One number can fly more than one leg on a day (AV 105: Bogotá–Cusco, then
 * Cusco–La Paz). {@code schedule} is the first leg and {@code otherLegs} holds
 * the rest, so a record from before legs existed still reads as it did; use
 * {@link #allLegs()} and {@link #narrowedTo(String)} rather than reading either
 * field for "the flight".
 */
public record FlightRecord(String flightNumber, LocalDate departureDate,
                           FlightSchedule schedule, FlightLive live,
                           Instant scheduleFetchedAt, Instant liveFetchedAt,
                           boolean notFound, List<FlightSchedule> otherLegs) {

    public FlightRecord {
        if (otherLegs != null && otherLegs.isEmpty()) otherLegs = null; // absent, not [], in the YAML
    }

    /** The shape from before legs: one schedule and nothing else. */
    public FlightRecord(String flightNumber, LocalDate departureDate, FlightSchedule schedule, FlightLive live,
                        Instant scheduleFetchedAt, Instant liveFetchedAt, boolean notFound) {
        this(flightNumber, departureDate, schedule, live, scheduleFetchedAt, liveFetchedAt, notFound, null);
    }

    /** Every leg this number flies that day, in the order they were stored. Not a getter, so it is never serialised. */
    public List<FlightSchedule> allLegs() {
        List<FlightSchedule> legs = new ArrayList<>();
        if (schedule != null) legs.add(schedule);
        if (otherLegs != null) legs.addAll(otherLegs);
        return legs;
    }

    /**
     * This record seen as one leg: the one leaving {@code departureIata}, else
     * the first. Live extras carry over (AviationStack answers per flight number
     * and date, and cannot tell legs apart).
     */
    public FlightRecord narrowedTo(String departureIata) {
        List<FlightSchedule> legs = allLegs();
        if (legs.size() < 2) return this;
        FlightSchedule chosen = legs.get(0);
        if (departureIata != null) {
            for (FlightSchedule leg : legs) {
                var airport = leg.departure() == null ? null : leg.departure().airport();
                if (airport != null && departureIata.equalsIgnoreCase(airport.iata())) { chosen = leg; break; }
            }
        }
        return new FlightRecord(flightNumber, departureDate, chosen, live, scheduleFetchedAt, liveFetchedAt, notFound, null);
    }

    /** A stored genuine empty answer. It expires by the negative TTL. */
    public static FlightRecord missing(String flightNumber, LocalDate date, Instant at) {
        return new FlightRecord(flightNumber, date, null, null, at, null, true);
    }

    public FlightRecord withLegs(List<FlightSchedule> legs, Instant at) {
        return new FlightRecord(flightNumber, departureDate, legs.get(0), live, at, liveFetchedAt, false,
                legs.size() > 1 ? legs.subList(1, legs.size()) : null);
    }

    public FlightRecord withLive(FlightLive live, Instant at) {
        return new FlightRecord(flightNumber, departureDate, schedule, live, scheduleFetchedAt, at, notFound, otherLegs);
    }
}
