package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
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

/**
 * ItineraryItem carries countryCodes — unlike ChecklistItem there
 * is no legacy single-id shape to keep reading, but the validation contract
 * ("every id must belong to this trip, de-duplicated, order preserved, null
 * means don't touch on update, empty clears") is the same one ChecklistService
 * already enforces, so this pins it down for the itinerary side too.
 */
class ItineraryServiceCountryLinksTest {

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

        destinations = new DestinationRepository(store, paths, locks);

        TripCountries tripCountries = new TripCountries(destinations);
        ChecklistRepository checklistRepository = new ChecklistRepository(store, paths, locks);
        ItineraryRepository itineraryRepository = new ItineraryRepository(store, paths, locks);
        BudgetRepository budgetRepository = new BudgetRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, "member@example.com", TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        ChecklistService checklistService =
                new ChecklistService(checklistRepository, itineraryRepository, access, tripCountries);

        service = new ItineraryService(itineraryRepository, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budgetRepository), new CountryCatalog(RestClient.create()), tripCountries);
    }

    /** A destination is what puts a country on the trip's list of choices. */
    private static Destination destination(String id, String name, String countryCode) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId("trip-1");
        destination.setName(name);
        destination.setCountryCode(countryCode);
        return destination;
    }

    private ItineraryService.Input input(List<String> countryCodes) {
        return new ItineraryService.Input(null, null, "Hotel in Cusco", null, null, null, null, null, null, null, null, countryCodes);
    }

    @Test
    void creatingAPlanWithNoCountriesGivesAnEmptyListRatherThanNull() {
        ItineraryItem plan = service.create(trip.getId(), USER_ID, input(null));

        assertThat(plan.getCountryCodes()).isNotNull().isEmpty();
    }

    @Test
    void creatingAPlanValidatesDedupesAndPreservesOrder() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));

        ItineraryItem plan = service.create(trip.getId(), USER_ID,
                input(List.of("BO", "PE", "BO")));

        assertThat(plan.getCountryCodes()).containsExactly("BO", "PE");
    }

    @Test
    void severalCitiesInOneCountryAreStillOneLink() {
        // The whole reason the link is a country: Cusco and Ollantaytambo are
        // one place as far as anything consuming this is concerned.
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "Ollantaytambo", "PE"));

        ItineraryItem plan = service.create(trip.getId(), USER_ID, input(List.of("PE", "PE")));

        assertThat(plan.getCountryCodes()).containsExactly("PE");
    }

    @Test
    void aLowercaseCodeIsAccepted() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));

        assertThat(service.create(trip.getId(), USER_ID, input(List.of("pe"))).getCountryCodes())
                .containsExactly("PE");
    }

    @Test
    void aCountryTheTripDoesNotVisitIsRejected() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));

        // 400 rather than 404: the country exists, it is this trip that does
        // not go there — and saving nothing quietly would look like success.
        assertThatThrownBy(() -> service.create(trip.getId(), USER_ID, input(List.of("BR"))))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(400));
    }

    @Test
    void updatingWithNullCountryCodesLeavesExistingLinksAlone() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        ItineraryItem plan = service.create(trip.getId(), USER_ID, input(List.of("PE")));

        ItineraryItem updated = service.update(trip.getId(), USER_ID, plan.getId(), input(null));

        assertThat(updated.getCountryCodes()).containsExactly("PE");
    }

    @Test
    void updatingWithAnEmptyListClearsEveryLink() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        ItineraryItem plan = service.create(trip.getId(), USER_ID, input(List.of("PE")));

        ItineraryItem updated = service.update(trip.getId(), USER_ID, plan.getId(), input(List.of()));

        assertThat(updated.getCountryCodes()).isEmpty();
    }

    @Test
    void updatingReplacesTheLinkList() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));
        ItineraryItem plan = service.create(trip.getId(), USER_ID, input(List.of("PE")));

        ItineraryItem updated = service.update(trip.getId(), USER_ID, plan.getId(), input(List.of("BO")));

        assertThat(updated.getCountryCodes()).containsExactly("BO");
    }
}
