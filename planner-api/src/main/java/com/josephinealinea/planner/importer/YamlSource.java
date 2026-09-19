package com.josephinealinea.planner.importer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.rates.infra.YamlRatesRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import com.josephinealinea.planner.weather.infra.YamlWeatherRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A YAML data directory, read through the application's own YAML repositories.
 *
 * <b>The repositories, not the files.</b> Reading through them is what makes
 * the import see exactly what the app sees in YAML mode: a budget row with no
 * {@code status} is CONFIRMED, a checklist item's single legacy
 * {@code destinationId} lands in the same transient holder the plural key
 * does, a trip with no display currency is EUR. Parsing the files any other
 * way would be a second reading of them that could disagree with the first.
 *
 * They are constructed here, pointed at the given directory, rather than
 * taken from the Spring context — an import runs in database mode, where the
 * context holds the JDBC implementations instead.
 *
 * <b>Read-only.</b> Nothing here calls a method that writes. The YAML
 * repositories only write on save, delete or replaceAll, and a directory that
 * is somebody's only copy of their trips is the last place to find out
 * otherwise.
 */
final class YamlSource {

    private static final TypeReference<List<YamlTripRepository.IndexEntry>> INDEX = new TypeReference<>() {};

    /** The per-trip entities whose files are looked at for orphans. */
    private static final List<String> PER_TRIP_ENTITIES =
            List.of("destinations", "checklist", "itinerary", "budget", "weather");

    final Path dir;
    final YamlStore store;
    final YamlPaths paths;
    final YamlUserRepository users;
    final YamlTripRepository trips;
    final YamlDestinationRepository destinations;
    final YamlChecklistRepository checklist;
    final YamlItineraryRepository itinerary;
    final YamlBudgetRepository budget;
    final YamlWeatherRepository weather;
    final YamlRatesRepository rates;

    private YamlSource(Path dir) {
        this.dir = dir;
        this.store = new YamlStore();
        // YamlPaths reads exactly two settings; the other eight groups are
        // never touched by it, so they are left null rather than invented.
        this.paths = new YamlPaths(new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), null),
                null, null, null, null, null, null, null, null));
        TripLocks locks = new TripLocks();
        this.users = new YamlUserRepository(store, paths, locks);
        this.trips = new YamlTripRepository(store, paths, locks);
        this.destinations = new YamlDestinationRepository(store, paths, locks);
        this.checklist = new YamlChecklistRepository(store, paths, locks);
        this.itinerary = new YamlItineraryRepository(store, paths, locks);
        this.budget = new YamlBudgetRepository(store, paths, locks);
        this.weather = new YamlWeatherRepository(store, paths, locks);
        this.rates = new YamlRatesRepository(store, paths);
    }

    static YamlSource at(Path dir) {
        return new YamlSource(dir.toAbsolutePath().normalize());
    }

    /** Whether this looks like a data directory at all — the guard against a typo importing nothing. */
    boolean looksLikeDataDirectory() {
        return Files.isRegularFile(paths.users());
    }

    /**
     * Every trip the app can see, in {@code trips/index.yml} order — the order
     * they are imported in.
     *
     * The index is what the app lists trips from, so it is what decides which
     * trips exist. Anything the app cannot see is reported in
     * {@code warnings} rather than imported: an index entry whose file is
     * gone, and a trip file the index does not name. Each trip is read fresh
     * from disk on every call.
     */
    List<Trip> trips(List<String> warnings) {
        List<YamlTripRepository.IndexEntry> index = store.readList(paths.tripIndex(), INDEX);
        List<Trip> found = new ArrayList<>();
        Set<String> indexed = new TreeSet<>();
        for (YamlTripRepository.IndexEntry entry : index) {
            indexed.add(entry.slug());
            Optional<Trip> trip = trips.findBySlug(entry.slug());
            if (trip.isEmpty()) {
                warnings.add("trips/index.yml lists '" + entry.slug() + "' but "
                        + dir.relativize(paths.trip(entry.slug())) + " does not exist — nothing to import for it");
                continue;
            }
            if (!entry.id().equals(trip.get().getId())) {
                warnings.add("trips/index.yml gives '" + entry.slug() + "' the id " + entry.id()
                        + " but its file says " + trip.get().getId() + " — imported with the file's id");
            }
            found.add(trip.get());
        }

        for (String slug : slugsWithFiles("trip")) {
            if (!indexed.contains(slug)) {
                warnings.add("travels/trip/" + slug + ".yml is not in trips/index.yml, so the app cannot see it"
                        + " — not imported");
            }
        }
        Set<String> imported = new TreeSet<>();
        found.forEach(trip -> imported.add(trip.getSlug()));
        for (String entity : PER_TRIP_ENTITIES) {
            for (String slug : slugsWithFiles(entity)) {
                if (!imported.contains(slug)) {
                    warnings.add("travels/" + entity + "/" + slug + ".yml belongs to no trip being imported"
                            + " — left behind");
                }
            }
        }
        return found;
    }

    /** The slugs that have a file under travels/&lt;entity&gt;/. */
    private List<String> slugsWithFiles(String entity) {
        Path folder = dir.resolve("travels").resolve(entity);
        if (!Files.isDirectory(folder)) return List.of();
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".yml"))
                    .map(name -> name.substring(0, name.length() - ".yml".length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + folder, e);
        }
    }
}
