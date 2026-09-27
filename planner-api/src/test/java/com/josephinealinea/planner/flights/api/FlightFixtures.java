package com.josephinealinea.planner.flights.api;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class FlightFixtures {

    private FlightFixtures() {}

    static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /** The real BT857 payload for 2026-10-24 (departure 04:30Z, arrival 07:00Z), trimmed. */
    static final String BT857 = """
            [{"departure":{"airport":{"icao":"EETN","iata":"TLL","name":"Tallinn Lennart Meri","municipalityName":"Tallinn",
                  "location":{"lat":59.4133,"lon":24.8328},"countryCode":"EE","timeZone":"Europe/Tallinn"},
                "scheduledTime":{"utc":"2026-10-24 04:30Z","local":"2026-10-24 07:30+03:00"}},
              "arrival":{"airport":{"icao":"EHAM","iata":"AMS","name":"Amsterdam Schiphol","municipalityName":"Amsterdam",
                  "location":{"lat":52.3086,"lon":4.763889},"countryCode":"NL","timeZone":"Europe/Amsterdam"},
                "scheduledTime":{"utc":"2026-10-24 07:00Z","local":"2026-10-24 09:00+02:00"}},
              "lastUpdatedUtc":"2026-06-02 08:13Z","number":"BT 857","status":"Expected",
              "aircraft":{"model":"Airbus A220-300"},"airline":{"name":"airBaltic"}}]
            """;


    static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    static final class Records implements FlightRecordRepository {
        final List<FlightRecord> rows = new ArrayList<>();

        @Override
        public synchronized Optional<FlightRecord> find(String number, LocalDate date) {
            return rows.stream().filter(r -> r.flightNumber().equals(number) && r.departureDate().equals(date)).findFirst();
        }

        @Override
        public synchronized void save(FlightRecord record) {
            rows.removeIf(r -> r.flightNumber().equals(record.flightNumber()) && r.departureDate().equals(record.departureDate()));
            rows.add(record);
        }

        @Override
        public synchronized List<FlightRecord> findAll() {
            return List.copyOf(rows);
        }
    }

    static final class Codeshares implements CodeshareRepository {
        final Map<String, CodeshareMapping> rows = new HashMap<>();

        @Override
        public Optional<String> operatingFor(String booked) {
            return Optional.ofNullable(rows.get(booked)).map(CodeshareMapping::operatingNumber);
        }

        @Override
        public void save(CodeshareMapping mapping) {
            rows.put(mapping.bookedNumber(), mapping);
        }

        @Override
        public List<CodeshareMapping> findAll() {
            return List.copyOf(rows.values());
        }
    }

    /** Counts acquisitions per service; enforces the cap like the real stores. */
    static final class Usage implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service, 0);
            if (now >= cap) return false;
            counts.put(service, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service, 0);
        }
    }
}
