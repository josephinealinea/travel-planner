package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.FlightNumbers;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.shared.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The lookup behind the form's icon: what flight is this, on this date?
 *
 * Order: the number as booked (or its known operating number) against
 * AeroDataBox; on a genuine miss that AeroDataBox has just answered (not a
 * stored one), AviationStack once to learn whether it is a codeshare; then
 * AeroDataBox again for the operating number. So an unresolved number costs at
 * most one AviationStack call per negative-TTL window, and none at all once
 * its pairing is stored (pairings are remembered for good).
 *
 * Best effort by design. AviationStack sees only flights around today, so a
 * weekly flight that is not operating this week may not resolve.
 */
@Service
public class FlightLookup {

    /** {@code schedule} is the first leg; {@code legs} holds every leg the number flies that day. */
    public record Outcome(Fetch.Status status, String operatingNumber, FlightSchedule schedule, List<FlightSchedule> legs) {
        public Outcome(Fetch.Status status, String operatingNumber, FlightSchedule schedule) {
            this(status, operatingNumber, schedule, schedule == null ? List.of() : List.of(schedule));
        }
    }

    private final FlightData data;
    private final AviationStackClient stack;
    private final CodeshareRepository codeshares;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightLookup(FlightData data, AviationStackClient stack, CodeshareRepository codeshares) {
        this(data, stack, codeshares, Clock.systemUTC());
    }

    public FlightLookup(FlightData data, AviationStackClient stack, CodeshareRepository codeshares, Clock clock) {
        this.data = data;
        this.stack = stack;
        this.codeshares = codeshares;
        this.clock = clock;
    }

    public Outcome lookup(String rawNumber, LocalDate date) {
        String booked = FlightNumbers.normalise(rawNumber);
        if (!FlightNumbers.valid(booked)) throw ApiException.badRequest("error.flight.numberInvalid");

        Optional<String> known = codeshares.operatingFor(booked);
        String target = known.orElse(booked);
        FlightData.Result result = data.schedule(target, date, null);

        // Only when AeroDataBox was actually asked just now and answered empty.
        // A stored miss already had its AviationStack question (or its outage)
        // within the negative TTL, and "no codeshare" is not stored, so asking
        // again on every retry would spend the scarce quota once per click.
        if (result.status() == Fetch.Status.NOT_FOUND && !result.fromCache() && known.isEmpty()) {
            Fetch<String> codeshare = stack.codeshareOf(booked);
            if (codeshare.status() == Fetch.Status.UNAVAILABLE) {
                // We could not finish the question, so we cannot say "not found".
                return new Outcome(Fetch.Status.UNAVAILABLE, null, null);
            }
            if (codeshare.status() == Fetch.Status.FOUND && !codeshare.value().equals(booked)) {
                target = codeshare.value();
                result = data.schedule(target, date, null);
                // Saved only when the operating flight is actually found, so a
                // wrong pairing is never remembered.
                if (result.status() == Fetch.Status.FOUND) {
                    codeshares.save(new CodeshareMapping(booked, target, clock.instant()));
                }
            }
        }

        if (result.status() != Fetch.Status.FOUND) return new Outcome(result.status(), null, null);
        String operating = target.equals(booked) ? null : target;
        var legs = result.record().allLegs();
        return new Outcome(Fetch.Status.FOUND, operating, legs.get(0), legs);
    }

    /** What the entry keeps: both numbers, both airports, both terminals. */
    public static FlightSnapshot snapshotOf(String booked, String operating, FlightSchedule schedule) {
        return new FlightSnapshot(FlightNumbers.normalise(booked), operating,
                schedule.departure().airport(), schedule.arrival().airport(),
                schedule.departure().terminal(), schedule.arrival().terminal(), schedule.airline());
    }
}
