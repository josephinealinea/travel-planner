package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.Fetch;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The one door to AeroDataBox data: cache first, then the service. The form
 * lookup, the public refresh and the nightly job all come through here, so
 * "how fresh is fresh" and "what counts as a miss" exist once.
 *
 * <b>Only a genuine empty answer is stored as a miss.</b> An outage, a cap or a
 * timeout is UNAVAILABLE and stores nothing; when a stale record exists it is
 * served with {@code stale} set, never blank. An empty answer over a good
 * record already held stores nothing either, and serves that record stale.
 */
@Service
public class FlightData {

    /**
     * {@code record} is null unless status is FOUND, or a stale one was served.
     * {@code fromCache} is true when the answer came from the stored record (a
     * fresh one, a stored miss, or one served stale) and false when this
     * request actually asked AeroDataBox.
     */
    public record Result(Fetch.Status status, FlightRecord record, boolean stale, boolean fromCache) {

        /** The shape from before {@code fromCache}: an answer this request asked for. */
        public Result(Fetch.Status status, FlightRecord record, boolean stale) {
            this(status, record, stale, false);
        }
    }

    private final AeroDataBoxClient client;
    private final FlightRecordRepository records;
    private final FlightFreshness rules;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public FlightData(AeroDataBoxClient client, FlightRecordRepository records, FlightFreshness rules) {
        this(client, records, rules, Clock.systemUTC());
    }

    FlightData(AeroDataBoxClient client, FlightRecordRepository records, FlightFreshness rules, Clock clock) {
        this.client = client;
        this.records = records;
        this.rules = rules;
        this.clock = clock;
    }

    public Result schedule(String number, LocalDate date, Instant departureHint) {
        Instant now = clock.instant();
        Optional<FlightRecord> held = records.find(number, date);

        if (held.isPresent()) {
            FlightRecord record = held.get();
            if (record.notFound()) {
                if (rules.negativeFresh(record, now)) return new Result(Fetch.Status.NOT_FOUND, null, false, true);
            } else if (rules.scheduleFresh(record, now, departureHint)) {
                return new Result(Fetch.Status.FOUND, record, false, true);
            }
        }

        Fetch<List<FlightSchedule>> fetched = client.legs(number, date);
        switch (fetched.status()) {
            case FOUND -> {
                FlightRecord base = held.filter(r -> !r.notFound())
                        .orElse(new FlightRecord(number, date, null, null, null, null, false));
                FlightRecord updated = base.withLegs(fetched.value(), now);
                records.save(updated);
                return new Result(Fetch.Status.FOUND, updated, false);
            }
            case NOT_FOUND -> {
                // One empty answer about a flight we already know is more likely a
                // provider blip than a cancelled route: keep the record (and its
                // live extras) and serve it stale, so the caller's failure
                // back-off applies. Only with nothing good held is a miss stored.
                Optional<FlightRecord> good = held.filter(r -> !r.notFound() && r.schedule() != null);
                if (good.isPresent()) return new Result(Fetch.Status.FOUND, good.get(), true, true);
                records.save(FlightRecord.missing(number, date, now));
                return new Result(Fetch.Status.NOT_FOUND, null, false);
            }
            default -> {
                // Unavailable: store nothing. Serve what we have, marked stale.
                return held.filter(r -> !r.notFound() && r.schedule() != null)
                        .map(r -> new Result(Fetch.Status.FOUND, r, true, true))
                        .orElse(new Result(Fetch.Status.UNAVAILABLE, null, false));
            }
        }
    }
}
