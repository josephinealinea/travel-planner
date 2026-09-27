// YamlCodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code data/flights/codeshares.yml}. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlCodeshareRepository implements CodeshareRepository {

    private static final TypeReference<List<CodeshareMapping>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlCodeshareRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized Optional<String> operatingFor(String bookedNumber) {
        return findAll().stream()
                .filter(m -> m.bookedNumber().equals(bookedNumber))
                .map(CodeshareMapping::operatingNumber)
                .findFirst();
    }

    @Override
    public synchronized void save(CodeshareMapping mapping) {
        List<CodeshareMapping> rows = store.readList(paths.flightCodeshares(), ROWS);
        rows.removeIf(m -> m.bookedNumber().equals(mapping.bookedNumber()));
        rows.add(mapping);
        store.write(paths.flightCodeshares(), rows);
    }

    @Override
    public synchronized List<CodeshareMapping> findAll() {
        return store.readList(paths.flightCodeshares(), ROWS);
    }
}
