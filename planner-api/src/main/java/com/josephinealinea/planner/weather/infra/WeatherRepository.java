package com.josephinealinea.planner.weather.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/**
 * The fifth per-trip entity, stored exactly like the other four: one YAML list
 * per trip, replaced wholesale under that trip's lock.
 *
 * Unlike the other four this one holds no member input — every row is written
 * by a lookup, and any of them can be deleted with nothing lost but a repeat
 * call. It is a cache that happens to have a file behind it, which is what
 * makes keeping it on disk safe; see WeatherRecord.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class WeatherRepository extends TripScopedYamlRepository<WeatherRecord> {

    public WeatherRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<WeatherRecord>>() {}, WeatherRecord::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.weather(tripSlug);
    }
}
