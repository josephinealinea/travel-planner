package com.josephinealinea.planner.flights.domain;

/** AeroDataBox's answer for one flight on one date. Times are ISO-8601 strings with offsets. */
public record FlightSchedule(String number, String status, String aircraftModel, String airline,
                             Leg departure, Leg arrival, String lastUpdatedUtc) {

    public record Leg(Airport airport, String scheduledLocal, String scheduledUtc,
                      String revisedLocal, String predictedLocal, String terminal) {}
}
