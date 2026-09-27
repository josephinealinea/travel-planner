package com.josephinealinea.planner.flights.domain;

/**
 * AviationStack's extras: gate, baggage belt, delay and actual times. Actual
 * times are airport wall-clock (AviationStack labels them UTC; the offset is
 * dropped on the way in). A real 0 minute delay is an answer, so it is Integer.
 */
public record FlightLive(String status, Leg departure, Leg arrival) {

    public record Leg(String gate, String baggageBelt, Integer delayMinutes, String actualLocal) {}
}
