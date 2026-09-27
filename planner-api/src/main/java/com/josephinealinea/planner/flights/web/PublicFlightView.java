package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.flights.api.FlightStatusService;
import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.flights.domain.FlightSchedule;

import java.time.Instant;

/**
 * What a published page receives. Its own type, with uniquely named nested
 * records (two "Leg" records would be merged into one schema in the API doc),
 * and no coordinates: a published page never ships them.
 */
public record PublicFlightView(String number, String operatingNumber, PublicSchedule schedule, PublicLive live,
                               Instant scheduleFetchedAt, Instant liveFetchedAt, boolean stale, long ttlSeconds) {

    public record PublicSchedule(String status, String aircraftModel, String airline,
                                 ScheduleLeg departure, ScheduleLeg arrival) {}

    public record ScheduleLeg(PublicAirport airport, String scheduledLocal, String scheduledUtc,
                              String revisedLocal, String predictedLocal, String terminal) {}

    public record PublicAirport(String iata, String name, String city, String countryCode, String timezone) {}

    public record PublicLive(String status, LiveLeg departure, LiveLeg arrival) {}

    public record LiveLeg(String gate, String baggageBelt, Integer delayMinutes, String actualLocal) {}

    public static PublicFlightView from(FlightStatusService.View v) {
        return new PublicFlightView(v.number(), v.operatingNumber(), schedule(v.schedule()), live(v.live()),
                v.scheduleFetchedAt(), v.liveFetchedAt(), v.stale(), v.ttlSeconds());
    }

    private static PublicSchedule schedule(FlightSchedule s) {
        return s == null ? null : new PublicSchedule(s.status(), s.aircraftModel(), s.airline(),
                leg(s.departure()), leg(s.arrival()));
    }

    private static ScheduleLeg leg(FlightSchedule.Leg l) {
        return l == null ? null : new ScheduleLeg(airport(l.airport()), l.scheduledLocal(), l.scheduledUtc(),
                l.revisedLocal(), l.predictedLocal(), l.terminal());
    }

    private static PublicAirport airport(Airport a) {
        return a == null ? null : new PublicAirport(a.iata(), a.name(), a.city(), a.countryCode(), a.timezone());
    }

    private static PublicLive live(FlightLive l) {
        return l == null ? null : new PublicLive(l.status(), liveLeg(l.departure()), liveLeg(l.arrival()));
    }

    private static LiveLeg liveLeg(FlightLive.Leg l) {
        return l == null ? null : new LiveLeg(l.gate(), l.baggageBelt(), l.delayMinutes(), l.actualLocal());
    }
}
