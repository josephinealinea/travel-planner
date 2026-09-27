// FlightRecordRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.FlightRecord;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** The install-wide flight cache. Not trip-scoped: it holds public schedule facts. */
public interface FlightRecordRepository {

    Optional<FlightRecord> find(String flightNumber, LocalDate departureDate);

    /** Upsert by (flightNumber, departureDate). */
    void save(FlightRecord record);

    List<FlightRecord> findAll();
}
