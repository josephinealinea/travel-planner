// CodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;

import java.util.List;
import java.util.Optional;

/** Booked number to operating number. Permanent, install-wide. */
public interface CodeshareRepository {

    Optional<String> operatingFor(String bookedNumber);

    /** Upsert by booked number. */
    void save(CodeshareMapping mapping);

    List<CodeshareMapping> findAll();
}
