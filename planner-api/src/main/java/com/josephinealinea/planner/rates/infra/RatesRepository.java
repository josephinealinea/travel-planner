package com.josephinealinea.planner.rates.infra;

import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * One file for the whole install: {@code data/rates.yml}.
 *
 * Not trip-scoped, so it does not extend TripScopedYamlRepository and takes no
 * lock — there is a single writer (the scheduled refresh) and YamlStore already
 * writes through a temp file and moves it into place, so a reader never sees a
 * half-written table.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class RatesRepository {

    private final YamlStore store;
    private final YamlPaths paths;

    public RatesRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    /** An empty table when nothing has been fetched yet — never null. */
    public RateTable load() {
        return store.read(paths.rates(), RateTable.class, new RateTable());
    }

    public void save(RateTable table) {
        store.write(paths.rates(), table);
    }
}
