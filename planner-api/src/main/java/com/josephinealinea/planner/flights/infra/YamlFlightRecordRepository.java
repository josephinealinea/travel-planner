// YamlFlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** {@code data/flights/records.yml}. {@code synchronized} is the lock: YAML mode is single-instance. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlFlightRecordRepository implements FlightRecordRepository {

    private static final TypeReference<List<FlightRecord>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlFlightRecordRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized Optional<FlightRecord> find(String flightNumber, LocalDate departureDate) {
        return findAll().stream()
                .filter(r -> r.flightNumber().equals(flightNumber) && r.departureDate().equals(departureDate))
                .findFirst();
    }

    @Override
    public synchronized void save(FlightRecord record) {
        List<FlightRecord> rows = store.readList(paths.flightRecords(), ROWS);
        rows.removeIf(r -> r.flightNumber().equals(record.flightNumber())
                && r.departureDate().equals(record.departureDate()));
        rows.add(record);
        store.write(paths.flightRecords(), rows);
    }

    @Override
    public synchronized List<FlightRecord> findAll() {
        return store.readList(paths.flightRecords(), ROWS);
    }
}
