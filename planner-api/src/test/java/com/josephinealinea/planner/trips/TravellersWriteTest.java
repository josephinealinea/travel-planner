package com.josephinealinea.planner.trips;

import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Setting, clearing and following travellers through the real services. See the travellers spec, section 4. */
class TravellersWriteTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private DestinationService destinationService;
    private ChecklistService checklistService;
    private ItineraryService itineraryService;
    private YamlDestinationRepository destinations;
    private YamlChecklistRepository checklist;
    private YamlItineraryRepository itinerary;
    private YamlBudgetRepository budget;

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
        var store = new YamlStore();
        var paths = new YamlPaths(props);
        var locks = new TripLocks();
        destinations = new YamlDestinationRepository(store, paths, locks);
        checklist = new YamlChecklistRepository(store, paths, locks);
        itinerary = new YamlItineraryRepository(store, paths, locks);
        budget = new YamlBudgetRepository(store, paths, locks);
        var trips = new YamlTripRepository(store, paths, locks);

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setStartDate(LocalDate.parse("2026-10-24"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.setDisplayCurrency("EUR");
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        var access = new TripAccessService(trips);
        var tripCountries = new TripCountries(destinations);
        var catalog = new CountryCatalog(RestClient.create());
        destinationService = new DestinationService(destinations, checklist, itinerary, budget,
                new ChecklistSeeder(), access, catalog, tripCountries);
        checklistService = new ChecklistService(checklist, itinerary, access, tripCountries);
        itineraryService = new ItineraryService(itinerary, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budget), catalog, tripCountries);
    }

    private Destination cusco(List<String> travellers) {
        return destinationService.create(TRIP_ID, ALEX, new DestinationService.Input(
                "Cusco", null, null, null, null, null, null, null, null, null, null,
                travellers, null)).destination();
    }

    @Test
    void aDestinationStoresWhoIsGoingAndSeedsItemsThatFollowIt() {
        Destination d = cusco(List.of(SAM));

        assertThat(destinations.findById(SLUG, d.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
        assertThat(checklist.findAll(SLUG)).isNotEmpty()
                .allSatisfy(item -> assertThat(item.getTravellerIds()).isNull());
    }

    @Test
    void anEditCanNameBuddiesSetTheWholeTripOrGoBackToFollowing() {
        ChecklistItem item = checklistService.create(TRIP_ID, ALEX,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Visas", null, List.of(), null, null));
        assertThat(item.getTravellerIds()).isNull();

        checklistService.update(TRIP_ID, SAM, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(SAM), null));
        ChecklistItem named = checklist.findById(SLUG, item.getId()).orElseThrow();
        assertThat(named.getTravellerIds()).containsExactly(SAM);
        assertThat(named.getUpdatedByUserId()).isEqualTo(SAM);

        checklistService.update(TRIP_ID, ALEX, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(), null));
        assertThat(checklist.findById(SLUG, item.getId()).orElseThrow().getTravellerIds()).isNotNull().isEmpty();

        checklistService.update(TRIP_ID, ALEX, item.getId(),
                new ChecklistService.Input(null, null, null, null, List.of(SAM), true));
        assertThat(checklist.findById(SLUG, item.getId()).orElseThrow().getTravellerIds()).isNull();
    }

    @Test
    void anEditThatSaysNothingAboutTravellersLeavesThemAlone() {
        Destination d = cusco(List.of(SAM));
        destinationService.update(TRIP_ID, ALEX, d.getId(), new DestinationService.Input(
                "Cusco!", null, null, null, null, null, null, null, null, null, null));
        assertThat(destinations.findById(SLUG, d.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
    }

    @Test
    void somebodyNotOnTheTripIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> cusco(List.of("user-stranger")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("travel buddies");
        assertThat(destinations.findAll(SLUG)).isEmpty();
    }

    // ── a plan's cost copies who's going, once ────────────────────────────

    private ItineraryItem planFromCuscoChecklist(BigDecimal cost, List<String> costSharers) {
        cusco(List.of(ALEX, SAM));
        ChecklistItem seeded = checklist.findAll(SLUG).get(0);
        return itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                seeded.getId(), null, "Bus to Cusco", LocalDateTime.of(2026, 10, 25, 9, 0), null,
                null, cost, "EUR", null, costSharers, null, List.of()));
    }

    @Test
    void aFirstCostWithNoSharersSentIsSharedByThePlansTravellers() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), null);

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds())
                .containsExactly(ALEX, SAM);
    }

    /**
     * Decided with the user: who's going only shapes a cost's default when the
     * plan is for particular buddies. A whole-trip plan's cost keeps the
     * default it always had, so ordinary plans behave exactly as before.
     */
    @Test
    void aWholeTripPlansCostKeepsTheDefaultItAlwaysHad() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.FOOD, "Welcome dinner", LocalDateTime.of(2026, 10, 24, 20, 0), null,
                null, new BigDecimal("80.00"), "EUR", null, null, null, List.of()));

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds()).isEmpty();
    }

    @Test
    void sharersSentExplicitlyAreUsedAsSentEvenTheWholeTrip() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), List.of());

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds()).isEmpty();
    }

    @Test
    void laterChangesToWhoIsGoingNeverMoveTheMoney() {
        ItineraryItem plan = planFromCuscoChecklist(new BigDecimal("60.00"), null);
        Destination cusco = destinations.findAll(SLUG).get(0);

        destinationService.update(TRIP_ID, ALEX, cusco.getId(), new DestinationService.Input(
                null, null, null, null, null, null, null, null, null, null, null, List.of(SAM), null));
        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(ALEX), null));

        assertThat(budget.findById(SLUG, plan.getBudgetItemId()).orElseThrow().getSharedByUserIds())
                .containsExactly(ALEX, SAM);
    }

    @Test
    void aCostAddedLaterToAnExistingPlanAlsoStartsWithItsTravellers() {
        ItineraryItem plan = planFromCuscoChecklist(null, null);
        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, new BigDecimal("40.00"), "EUR", null, null, null, null));

        String rowId = itinerary.findById(SLUG, plan.getId()).orElseThrow().getBudgetItemId();
        assertThat(budget.findById(SLUG, rowId).orElseThrow().getSharedByUserIds()).containsExactly(ALEX, SAM);
    }

    @Test
    void aStaysLaterDaysStoreNothingAndRefuseTravellersOfTheirOwn() {
        ItineraryItem stay = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.of(2026, 10, 25, 15, 0), LocalDateTime.of(2026, 10, 27, 11, 0),
                null, null, null, null, null, null, List.of(), List.of(SAM), null));
        List<ItineraryItem> days = itinerary.findAll(SLUG);
        ItineraryItem night = days.stream().filter(i -> stay.getId().equals(i.getPlanId())).findFirst().orElseThrow();

        assertThat(itinerary.findById(SLUG, stay.getId()).orElseThrow().getTravellerIds()).containsExactly(SAM);
        assertThat(night.getTravellerIds()).isNull();
        assertThatThrownBy(() -> itineraryService.update(TRIP_ID, ALEX, night.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, null, null, List.of(ALEX), null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("follows its booking");
    }
}
