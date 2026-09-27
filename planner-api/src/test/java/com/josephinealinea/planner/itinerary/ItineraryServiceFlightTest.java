package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItineraryServiceFlightTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String USER_ID = "user-1";

    private DestinationRepository destinations;
    private ItineraryService service;
    private Trip trip;

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

        TripCountries tripCountries = new TripCountries(destinations);
        ChecklistRepository checklistRepository = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itineraryRepository = new YamlItineraryRepository(store, paths, locks);
        BudgetRepository budgetRepository = new YamlBudgetRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        ChecklistService checklistService =
                new ChecklistService(checklistRepository, itineraryRepository, access, tripCountries);

        service = new ItineraryService(itineraryRepository, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budgetRepository), new CountryCatalog(RestClient.create()), tripCountries);
    }

    private static ItineraryService.Input full(ChecklistCategory category, String description, FlightSnapshot flight) {
        return new ItineraryService.Input(null, category, description, null, null, null, null, null,
                null, null, null, null, null, null, null, flight);
    }

    private ItineraryService.Input inputWithFlight(ChecklistCategory category, FlightSnapshot flight) {
        return full(category, "KLM flight", flight);
    }

    private ItineraryService.Input patchWithFlight(FlightSnapshot flight) {
        return full(null, null, flight);
    }

    private ItineraryService.Input patchWithCategory(ChecklistCategory category) {
        return full(category, null, null);
    }

    private ItineraryItem reload(String id) {
        return service.list(trip.getId(), USER_ID).stream()
                .filter(i -> i.getId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aFlightIsAcceptedOnATransportEntryAndNormalised() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("kl 2842", "bt857", null, null, null, null)));

        assertThat(plan.getFlight().number()).isEqualTo("KL2842");
        assertThat(plan.getFlight().operatingNumber()).isEqualTo("BT857");
    }

    @Test
    void aFlightOnANonTransportEntryIsRefused() {
        assertThatThrownBy(() -> service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.FOOD, new FlightSnapshot("KL2842", null, null, null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400))
                .hasMessageContaining("error.flight.transportOnly");
    }

    @Test
    void aBadNumberIsRefused() {
        assertThatThrownBy(() -> service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("hello", null, null, null, null, null))))
                .hasMessageContaining("error.flight.numberInvalid");
    }

    @Test
    void absentLeavesTheFlightAndAnEmptyNumberClearsIt() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", "BT857", null, null, null, null)));

        service.update(trip.getId(), USER_ID, plan.getId(), patchWithFlight(null));
        assertThat(reload(plan.getId()).getFlight()).isNotNull();

        service.update(trip.getId(), USER_ID, plan.getId(),
                patchWithFlight(new FlightSnapshot("", null, null, null, null, null)));
        assertThat(reload(plan.getId()).getFlight()).isNull();
    }

    @Test
    void movingAnEntryOffTransportClearsItsFlight() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", null, null, null, null, null)));

        service.update(trip.getId(), USER_ID, plan.getId(), patchWithCategory(ChecklistCategory.FOOD));

        assertThat(reload(plan.getId()).getFlight()).isNull();
    }

    @Test
    void stayNightsCarryNoFlight() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                java.time.LocalDateTime.of(2026, 10, 24, 15, 0), java.time.LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null);
        service.create(trip.getId(), USER_ID, stay);

        List<ItineraryItem> all = service.list(trip.getId(), USER_ID);
        assertThat(all.size()).isGreaterThan(1);
        assertThat(all).allSatisfy(i -> assertThat(i.getFlight()).isNull());
    }

    // ---- F7: the operating number is held to the same shape as the booked one ----

    @Test
    void aValidOperatingNumberIsAcceptedAndNormalised() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", " bt 857 ", null, null, null, null)));

        assertThat(plan.getFlight().operatingNumber()).isEqualTo("BT857");
    }

    @Test
    void aGarbageOperatingNumberIsRefused() {
        assertThatThrownBy(() -> service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", "hello world", null, null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400))
                .hasMessageContaining("error.flight.numberInvalid");
    }

    @Test
    void aBlankOperatingNumberIsStoredAsNone() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", "   ", null, null, null, null)));

        assertThat(plan.getFlight().operatingNumber()).isNull();
    }

    // ---- T3 ----

    @Test
    void onePatchThatMovesToFoodAndSendsAFlightIsRefused() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, inputWithFlight(
                ChecklistCategory.TRANSPORTATION, new FlightSnapshot("KL2842", null, null, null, null, null)));

        assertThatThrownBy(() -> service.update(trip.getId(), USER_ID, plan.getId(), full(ChecklistCategory.FOOD, null,
                new FlightSnapshot("KL2842", null, null, null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400))
                .hasMessageContaining("error.flight.transportOnly");
        assertThat(reload(plan.getId()).getCategory()).isEqualTo(ChecklistCategory.TRANSPORTATION);
    }

    @Test
    void aLaterDayOfAStayCannotBeGivenAFlight() {
        ItineraryService.Input stay = new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel",
                java.time.LocalDateTime.of(2026, 10, 24, 15, 0), java.time.LocalDateTime.of(2026, 10, 26, 11, 0),
                null, null, null, null, null, null, null, null, null, null, null);
        service.create(trip.getId(), USER_ID, stay);
        ItineraryItem night = service.list(trip.getId(), USER_ID).stream()
                .filter(i -> i.getPlanId() != null).findFirst().orElseThrow();

        // Even moved to transport in the same patch: the day follows its booking.
        assertThatThrownBy(() -> service.update(trip.getId(), USER_ID, night.getId(),
                full(ChecklistCategory.TRANSPORTATION, null, new FlightSnapshot("KL2842", null, null, null, null, null))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400))
                .hasMessageContaining("error.flight.dayFollowsBooking");
        assertThat(reload(night.getId()).getFlight()).isNull();
    }
}
