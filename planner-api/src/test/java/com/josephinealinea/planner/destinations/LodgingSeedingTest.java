package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When the accommodation checklist item appears, and when it does not.
 *
 * Somewhere to sleep is the one seeded item the dates decide. A destination
 * saved before its dates are known, or a day trip that starts and ends on the
 * same date, needs no room — so it gets transport and activities only. Dates
 * usually arrive afterwards though, and the moment they cover a night is the
 * moment booking a room becomes a real job, so the item is seeded then.
 *
 * The rule that makes that safe is "once per destination, ever". Without it,
 * an item somebody deleted on purpose would reappear the next time anyone
 * touched the dates.
 */
class LodgingSeedingTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String USER_ID = "user-1";

    private DestinationService service;
    private ChecklistRepository checklist;
    private DestinationRepository destinations;

    @BeforeEach
    void setUp(@TempDir Path dir) {
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

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        destinations = new YamlDestinationRepository(store, paths, locks);
        checklist = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itinerary = new YamlItineraryRepository(store, paths, locks);
        BudgetRepository budget = new YamlBudgetRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.setStartDate(LocalDate.parse("2026-10-20"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.getMembers().add(new TripMember(USER_ID, TripRole.OWNER, null));
        trips.save(trip);

        service = new DestinationService(destinations, checklist, itinerary, budget,
                new ChecklistSeeder(), new TripAccessService(trips),
                new CountryCatalog(RestClient.create()), new TripCountries(destinations));
    }

    private DestinationService.Input suppressed(String start, String end) {
        return new DestinationService.Input("Cusco", "PE", null, null, null, null,
                start == null ? null : LocalDate.parse(start),
                end == null ? null : LocalDate.parse(end),
                null, true);
    }

    private DestinationService.Input input(String start, String end) {
        return new DestinationService.Input("Cusco", "PE", null, null, null, null, start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, null);
    }

    private List<ChecklistCategory> seededCategories() {
        return checklist.findAllOrdered(SLUG).stream().map(ChecklistItem::getCategory).toList();
    }

    @Test
    void aDestinationWithNoDatesSeedsNoAccommodation() {
        service.create(TRIP_ID, USER_ID, input(null, null));

        assertThat(seededCategories())
                .containsExactly(ChecklistCategory.TRANSPORTATION, ChecklistCategory.ACTIVITIES);
    }

    @Test
    void aDayTripSeedsNoAccommodation() {
        // 24-Oct to 24-Oct: there and back, no night anywhere.
        service.create(TRIP_ID, USER_ID, input("2026-10-24", "2026-10-24"));

        assertThat(seededCategories())
                .containsExactly(ChecklistCategory.TRANSPORTATION, ChecklistCategory.ACTIVITIES);
    }

    @Test
    void aStayOfOneNightSeedsAccommodationUpFront() {
        service.create(TRIP_ID, USER_ID, input("2026-10-24", "2026-10-25"));

        assertThat(seededCategories()).containsExactly(ChecklistCategory.TRANSPORTATION,
                ChecklistCategory.LODGING, ChecklistCategory.ACTIVITIES);
        assertThat(checklist.findAllOrdered(SLUG))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan 1N accommodation in Cusco");
    }

    @Test
    void fillingInDatesLaterSeedsTheAccommodationItemThen() {
        Destination created = service.create(TRIP_ID, USER_ID, input(null, null)).destination();
        assertThat(seededCategories()).doesNotContain(ChecklistCategory.LODGING);

        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-25", "2026-10-31"));

        assertThat(seededCategories()).contains(ChecklistCategory.LODGING);
        assertThat(checklist.findAllOrdered(SLUG))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan 6N accommodation in Cusco");
    }

    @Test
    void growingADayTripIntoAStaySeedsIt() {
        Destination created = service.create(TRIP_ID, USER_ID,
                input("2026-10-24", "2026-10-24")).destination();

        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-24", "2026-10-26"));

        assertThat(checklist.findAllOrdered(SLUG))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan 2N accommodation in Cusco");
    }

    @Test
    void editingTheDatesAgainDoesNotSeedASecondItem() {
        Destination created = service.create(TRIP_ID, USER_ID, input(null, null)).destination();
        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-25", "2026-10-31"));
        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-26", "2026-11-01"));

        assertThat(seededCategories()).filteredOn(ChecklistCategory.LODGING::equals).hasSize(1);
    }

    @Test
    void anAccommodationItemDeletedOnPurposeDoesNotComeBack() {
        Destination created = service.create(TRIP_ID, USER_ID,
                input("2026-10-25", "2026-10-31")).destination();
        ChecklistItem lodging = checklist.findAllOrdered(SLUG).stream()
                .filter(item -> item.getCategory() == ChecklistCategory.LODGING)
                .findFirst().orElseThrow();
        checklist.delete(SLUG, lodging.getId());

        // Somebody decided this trip does not need the item. Touching the
        // dates is not a request to reinstate it.
        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-25", "2026-11-02"));

        assertThat(seededCategories()).doesNotContain(ChecklistCategory.LODGING);
    }

    @Test
    void shrinkingAStayToADayTripLeavesTheItemAlone() {
        Destination created = service.create(TRIP_ID, USER_ID,
                input("2026-10-25", "2026-10-31")).destination();

        service.update(TRIP_ID, USER_ID, created.getId(), input("2026-10-25", "2026-10-25"));

        // It may already carry a plan, a booking reference and a budget row.
        // Correcting a date must not throw any of that away.
        assertThat(seededCategories()).contains(ChecklistCategory.LODGING);
        assertThat(checklist.findAllOrdered(SLUG))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan 6N accommodation in Cusco");
    }

    // ── suppressing the checklist entirely ──────────

    /**
     * "Suppress auto-generated checklist" on the destination form. Nothing is
     * seeded — not the accommodation item the dates would otherwise produce,
     * and not the transport and activities ones either.
     */
    @Test
    void aSuppressedDestinationSeedsNothing() {
        service.create(TRIP_ID, USER_ID, suppressed("2026-10-25", "2026-10-31"));

        assertThat(checklist.findAll(SLUG)).isEmpty();
    }

    /**
     * The reason the flag is stored rather than read once off the form.
     *
     * The accommodation item is seeded on a later edit too, when dates arrive
     * that cover a night. Without remembering the answer, suppressing the
     * checklist and then filling in the dates would quietly produce the one
     * item the member said they did not want.
     */
    @Test
    void fillingInDatesLaterStillSeedsNothingForASuppressedDestination() {
        Destination created = service.create(TRIP_ID, USER_ID, suppressed(null, null)).destination();
        assertThat(checklist.findAll(SLUG)).isEmpty();

        service.update(TRIP_ID, USER_ID, created.getId(),
                new DestinationService.Input(null, null, null, null, null, null,
                        LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-31"), null, null));

        assertThat(checklist.findAll(SLUG)).isEmpty();
        assertThat(destinations.findById(SLUG, created.getId()).orElseThrow().seedsNoChecklist())
                .isTrue();
    }

    /** Unticking it later lets the accommodation item appear, since the dates need one. */
    @Test
    void clearingTheFlagLetsTheAccommodationItemAppear() {
        Destination created = service.create(TRIP_ID, USER_ID,
                suppressed("2026-10-25", "2026-10-31")).destination();
        assertThat(checklist.findAll(SLUG)).isEmpty();

        service.update(TRIP_ID, USER_ID, created.getId(),
                new DestinationService.Input(null, null, null, null, null, null,
                        null, null, null, false));

        // Only the lodging one: transport and activities are seeded at create
        // time and nothing re-derives them later, exactly as renaming a
        // destination does not rewrite its items.
        assertThat(checklist.findAll(SLUG))
                .extracting(ChecklistItem::getCategory)
                .containsExactly(ChecklistCategory.LODGING);
    }

    /** The flag is absent rather than false when nobody asked for it. */
    @Test
    void anOrdinaryDestinationStoresNoSuppressionFlagAtAll() {
        Destination created = service.create(TRIP_ID, USER_ID,
                input("2026-10-25", "2026-10-31")).destination();

        assertThat(destinations.findById(SLUG, created.getId()).orElseThrow()
                .getSuppressChecklist()).isNull();
        assertThat(checklist.findAll(SLUG)).hasSize(3);
    }
}
