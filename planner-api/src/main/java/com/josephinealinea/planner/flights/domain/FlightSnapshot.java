package com.josephinealinea.planner.flights.domain;

/**
 * What an itinerary entry keeps about its flight: only facts that do not change
 * day to day. Gate, baggage and delay are deliberately not here; they live in
 * the cache with a fetch time. A snapshot holding only {@code number} is valid:
 * a member who never uses the lookup still gets it on the plan card.
 */
public record FlightSnapshot(String number, String operatingNumber, Airport from, Airport to,
                             String terminalFrom, String terminalTo, String airline) {

    /** The shape from before the airline's name was recorded. */
    public FlightSnapshot(String number, String operatingNumber, Airport from, Airport to,
                          String terminalFrom, String terminalTo) {
        this(number, operatingNumber, from, to, terminalFrom, terminalTo, null);
    }
}
