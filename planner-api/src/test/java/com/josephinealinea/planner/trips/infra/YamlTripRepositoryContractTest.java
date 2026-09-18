package com.josephinealinea.planner.trips.infra;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.domain.Trip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The trip contract against the YAML files in a temp directory. Always runs. */
class YamlTripRepositoryContractTest extends TripRepositoryContract {

    private YamlStore store;
    private YamlPaths paths;
    private YamlTripRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        store = new YamlStore();
        paths = new YamlPaths(props);
        repository = new YamlTripRepository(store, paths, new TripLocks());
    }

    @Override
    protected TripRepository repository() {
        return repository;
    }

    @Override
    protected void givenUser(String userId) {
        // users.yml is a separate file the trip file never refers into.
    }

    /** The five per-trip files beside the trip's own. */
    private List<Path> perTripFiles(Trip trip) {
        String slug = trip.getSlug();
        return List.of(paths.destinations(slug), paths.checklist(slug), paths.itinerary(slug),
                paths.budget(slug), paths.weather(slug));
    }

    @Override
    protected void givenDataInEveryPerTripStore(Trip trip) {
        for (Path file : perTripFiles(trip)) store.write(file, List.of(Map.of("id", "row-1")));
    }

    @Override
    protected long perTripDataRemaining(Trip trip) {
        return perTripFiles(trip).stream().filter(Files::exists).count();
    }
}
