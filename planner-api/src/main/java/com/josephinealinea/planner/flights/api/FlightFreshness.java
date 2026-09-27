package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * The time rules for the flight cache, as pure functions of "now" so a test can
 * pin them. Nothing here reads a clock or touches a store.
 */
@Component
public class FlightFreshness {

    /** AeroDataBox statuses after which nothing more will change. */
    private static final Set<String> ENDED = Set.of("Arrived", "Landed", "Canceled");

    private static final Duration LANDED_GRACE = Duration.ofHours(3);

    /** The shortest a browser is told to keep an answer, in seconds. */
    static final long MIN_BROWSER_TTL = 60;

    private final FlightProperties props;

    public FlightFreshness(FlightProperties props) {
        this.props = props;
    }

    /** How long a schedule fetched now is trusted, by how close departure is. */
    public Duration scheduleTtl(Instant now, Instant departure) {
        if (departure == null) return props.ttl().farAhead();
        Duration until = Duration.between(now, departure);
        if (until.compareTo(Duration.ofHours(24)) <= 0) return props.ttl().withinOneDay();
        if (until.compareTo(Duration.ofHours(72)) <= 0) return props.ttl().withinThreeDays();
        return props.ttl().farAhead();
    }

    /**
     * Frozen once landed (a finished flight never changes); otherwise fresh while
     * younger than the TTL of the tier it is in now.
     */
    public boolean scheduleFresh(FlightRecord record, Instant now, Instant departureHint) {
        if (record.schedule() == null || record.scheduleFetchedAt() == null) return false;
        FlightSchedule leg = active(record, now);
        if (landed(leg, now)) return true;
        Instant departure = departureOf(leg);
        if (departure == null) departure = departureHint;
        Duration age = Duration.between(record.scheduleFetchedAt(), now);
        return age.compareTo(scheduleTtl(now, departure)) < 0;
    }

    /**
     * How long a reader's browser may keep this answer before asking again:
     * what is left of the record's tier TTL, cut short at the next moment the
     * answer's rules change (72 h and 24 h before departure, the live window
     * opening, landing), at most the live TTL inside the live window, and never
     * under a minute. Sending the whole tier TTL instead would let a click 73 h
     * out hide every later change, the live window included.
     *
     * A landed flight never changes, so it keeps its tier TTL.
     */
    public long browserTtlSeconds(FlightRecord record, Instant now) {
        FlightSchedule schedule = active(record, now);
        Instant departure = departureOf(schedule);
        Duration tier = scheduleTtl(now, departure);
        if (schedule != null && landed(schedule, now)) return Math.max(MIN_BROWSER_TTL, tier.toSeconds());

        Duration age = record.scheduleFetchedAt() == null ? tier : Duration.between(record.scheduleFetchedAt(), now);
        Duration ttl = tier.minus(age);
        if (ttl.isNegative()) ttl = Duration.ZERO;
        if (departure != null) {
            Instant arrival = schedule.arrival() == null ? null : parse(schedule.arrival().scheduledUtc());
            for (Instant boundary : new Instant[] {
                    departure.minus(Duration.ofHours(72)),
                    departure.minus(Duration.ofHours(24)),
                    departure.minus(props.live().window()),
                    arrival == null ? null : arrival.plus(LANDED_GRACE)}) {
                if (boundary == null || !boundary.isAfter(now)) continue;
                Duration until = Duration.between(now, boundary);
                if (until.compareTo(ttl) < 0) ttl = until;
            }
            if (inLiveWindow(now, departure) && liveTtl().compareTo(ttl) < 0) ttl = liveTtl();
        }
        return Math.max(MIN_BROWSER_TTL, ttl.toSeconds());
    }

    /**
     * The leg the record's freshness follows: the first that has not landed, or
     * the last once all have. Frozen-once-landed must not freeze a second leg
     * that has not flown yet just because the first has.
     */
    public FlightSchedule active(FlightRecord record, Instant now) {
        List<FlightSchedule> legs = record.allLegs();
        for (FlightSchedule leg : legs) if (!landed(leg, now)) return leg;
        return legs.isEmpty() ? null : legs.get(legs.size() - 1);
    }

    public boolean negativeFresh(FlightRecord record, Instant now) {
        return record.notFound() && record.scheduleFetchedAt() != null
                && Duration.between(record.scheduleFetchedAt(), now).compareTo(props.negativeTtl()) < 0;
    }

    public boolean liveFresh(FlightRecord record, Instant now) {
        return record.liveFetchedAt() != null
                && Duration.between(record.liveFetchedAt(), now).compareTo(props.live().ttl()) < 0;
    }

    public Duration liveTtl() {
        return props.live().ttl();
    }

    /** From the window before departure until landing (the caller checks landed). */
    public boolean inLiveWindow(Instant now, Instant departure) {
        return departure != null && !now.isBefore(departure.minus(props.live().window()));
    }

    /** An ended status, or three hours past the scheduled arrival, so a stuck status cannot stay live forever. */
    public boolean landed(FlightSchedule schedule, Instant now) {
        if (schedule.status() != null && ENDED.contains(schedule.status())) return true;
        Instant arrival = parse(schedule.arrival() == null ? null : schedule.arrival().scheduledUtc());
        return arrival != null && !now.isBefore(arrival.plus(LANDED_GRACE));
    }

    public static Instant departureOf(FlightSchedule schedule) {
        return schedule == null || schedule.departure() == null ? null : parse(schedule.departure().scheduledUtc());
    }

    private static Instant parse(String iso) {
        return iso == null ? null : OffsetDateTime.parse(iso).toInstant();
    }
}
