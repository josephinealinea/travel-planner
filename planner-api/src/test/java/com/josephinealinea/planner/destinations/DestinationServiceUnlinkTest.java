package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
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

/**
 * What deleting a destination does to the items linked to a country.
 *
 * Those links are countries, so most deletions now unlink nothing: losing
 * Cusco leaves a Peru-linked item exactly as it was, because the trip still
 * goes to Peru through Ollantaytambo. Only losing a country's last destination
 * detaches anything, and even then the item itself survives — losing a place
 * must never discard the planning done against it.
 */
class DestinationServiceUnlinkTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String USER_ID = "user-1";

    private DestinationRepository destinations;
    private ChecklistRepository checklist;
    private ItineraryRepository itinerary;
    private BudgetRepository budget;
    private DestinationService service;
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
        checklist = new ChecklistRepository(store, paths, locks);
        itinerary = new ItineraryRepository(store, paths, locks);
        budget = new BudgetRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.getMembers().add(new TripMember(USER_ID, "member@example.com", TripRole.OWNER, null));
        trips.save(trip);

        service = new DestinationService(destinations, checklist, itinerary, budget, new ChecklistSeeder(),
                new TripAccessService(trips), new CountryCatalog(RestClient.create()), tripCountries);
    }

    private static Destination destination(String id, String name, String countryCode) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId("trip-1");
        destination.setName(name);
        destination.setCountryCode(countryCode);
        return destination;
    }

    private static ChecklistItem item(String id, List<String> countryCodes) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setTripId("trip-1");
        item.setCategory(ChecklistCategory.OTHERS);
        item.setDescription("Item " + id);
        item.setCountryCodes(countryCodes);
        return item;
    }

    private static ItineraryItem plan(String id, List<String> countryCodes) {
        ItineraryItem plan = new ItineraryItem();
        plan.setId(id);
        plan.setTripId("trip-1");
        plan.setCategory(ChecklistCategory.OTHERS);
        plan.setDescription("Plan " + id);
        plan.setCountryCodes(countryCodes);
        return plan;
    }

    private static BudgetItem budgetItem(String id, List<String> countryCodes) {
        BudgetItem item = new BudgetItem();
        item.setId(id);
        item.setTripId("trip-1");
        item.setCategory(ChecklistCategory.OTHERS);
        item.setDescription("Expense " + id);
        item.setCountryCodes(countryCodes);
        return item;
    }

    @Test
    void deletingOneOfTwoCitiesInACountryUnlinksNothing() {
        // The point of linking to countries: Cusco going away does not make a
        // Peru-linked item any less true, because the trip still goes to Peru.
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "Ollantaytambo", "PE"));
        checklist.save(SLUG, item("item-1", List.of("PE")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.checklistItemsUnlinked()).isZero();
        assertThat(checklist.findAll(SLUG).get(0).getCountryCodes()).containsExactly("PE");
    }

    @Test
    void deletingACountrysLastDestinationUnlinksIt() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));
        checklist.save(SLUG, item("item-1", List.of("PE", "BO")));

        service.delete(trip.getId(), USER_ID, "dest-1");

        // Peru is gone from the trip, so the link to it stops being true —
        // but the item itself stays, with its other country intact.
        assertThat(checklist.findAll(SLUG).get(0).getCountryCodes()).containsExactly("BO");
    }

    @Test
    void aDestinationWithNoCountryUnlinksNothing() {
        // Some places have no gazetteer entry, so they carry no country and
        // there is nothing for their removal to detach.
        destinations.save(SLUG, destination("dest-1", "Machu Picchu Pueblo", null));
        destinations.save(SLUG, destination("dest-2", "Cusco", "PE"));
        checklist.save(SLUG, item("item-1", List.of("PE")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.checklistItemsUnlinked()).isZero();
        assertThat(checklist.findAll(SLUG).get(0).getCountryCodes()).containsExactly("PE");
    }

    @Test
    void deletingADestinationUnlinksOnlyThatOneFromEveryItemAndKeepsTheItems() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));

        checklist.save(SLUG, item("item-1", List.of("PE", "BO")));
        checklist.save(SLUG, item("item-2", List.of("PE")));
        checklist.save(SLUG, item("item-3", List.of("BO")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.checklistItemsUnlinked()).isEqualTo(2);

        assertThat(destinations.findAll(SLUG)).extracting(Destination::getId).containsExactly("dest-2");

        List<ChecklistItem> remaining = checklist.findAll(SLUG);
        assertThat(remaining).extracting(ChecklistItem::getId)
                .containsExactlyInAnyOrder("item-1", "item-2", "item-3");

        ChecklistItem itemOne = remaining.stream().filter(i -> i.getId().equals("item-1")).findFirst().orElseThrow();
        assertThat(itemOne.getCountryCodes()).containsExactly("BO");

        ChecklistItem itemTwo = remaining.stream().filter(i -> i.getId().equals("item-2")).findFirst().orElseThrow();
        assertThat(itemTwo.getCountryCodes()).isEmpty();

        ChecklistItem itemThree = remaining.stream().filter(i -> i.getId().equals("item-3")).findFirst().orElseThrow();
        assertThat(itemThree.getCountryCodes()).containsExactly("BO");
    }

    /**
     * Itinerary entries get the same unlink-not-delete treatment as checklist
     * items. Without this, deleting a destination would leave itinerary
     * entries pointing at an id that no longer resolves to anything.
     */
    @Test
    void deletingADestinationUnlinksItineraryEntriesTooAndKeepsThem() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));

        itinerary.save(SLUG, plan("plan-1", List.of("PE", "BO")));
        itinerary.save(SLUG, plan("plan-2", List.of("PE")));
        itinerary.save(SLUG, plan("plan-3", List.of("BO")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.itineraryItemsUnlinked()).isEqualTo(2);

        List<ItineraryItem> remaining = itinerary.findAll(SLUG);
        assertThat(remaining).extracting(ItineraryItem::getId)
                .containsExactlyInAnyOrder("plan-1", "plan-2", "plan-3");

        ItineraryItem planOne = remaining.stream().filter(p -> p.getId().equals("plan-1")).findFirst().orElseThrow();
        assertThat(planOne.getCountryCodes()).containsExactly("BO");

        ItineraryItem planTwo = remaining.stream().filter(p -> p.getId().equals("plan-2")).findFirst().orElseThrow();
        assertThat(planTwo.getCountryCodes()).isEmpty();

        ItineraryItem planThree = remaining.stream().filter(p -> p.getId().equals("plan-3")).findFirst().orElseThrow();
        assertThat(planThree.getCountryCodes()).containsExactly("BO");
    }

    /**
     * Budget rows get the same unlink-not-delete treatment as checklist items
     * and itinerary entries. Without this, deleting a destination would leave
     * a manual expense — or a plan's budget row — pointing at an id that no
     * longer resolves to anything, which is exactly the gap the country
     * breakdown chart cannot tolerate.
     */
    @Test
    void deletingADestinationUnlinksBudgetRowsTooAndKeepsThem() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));

        budget.save(SLUG, budgetItem("expense-1", List.of("PE", "BO")));
        budget.save(SLUG, budgetItem("expense-2", List.of("PE")));
        budget.save(SLUG, budgetItem("expense-3", List.of("BO")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.budgetItemsUnlinked()).isEqualTo(2);

        List<BudgetItem> remaining = budget.findAll(SLUG);
        assertThat(remaining).extracting(BudgetItem::getId)
                .containsExactlyInAnyOrder("expense-1", "expense-2", "expense-3");

        BudgetItem expenseOne = remaining.stream().filter(i -> i.getId().equals("expense-1")).findFirst().orElseThrow();
        assertThat(expenseOne.getCountryCodes()).containsExactly("BO");

        BudgetItem expenseTwo = remaining.stream().filter(i -> i.getId().equals("expense-2")).findFirst().orElseThrow();
        assertThat(expenseTwo.getCountryCodes()).isEmpty();

        BudgetItem expenseThree = remaining.stream().filter(i -> i.getId().equals("expense-3")).findFirst().orElseThrow();
        assertThat(expenseThree.getCountryCodes()).containsExactly("BO");
    }

    @Test
    void unrelatedChecklistItineraryAndBudgetLinksSurviveAnUnrelatedDelete() {
        destinations.save(SLUG, destination("dest-1", "Cusco", "PE"));
        destinations.save(SLUG, destination("dest-2", "La Paz", "BO"));

        checklist.save(SLUG, item("item-1", List.of("BO")));
        itinerary.save(SLUG, plan("plan-1", List.of("BO")));
        budget.save(SLUG, budgetItem("expense-1", List.of("BO")));

        DestinationService.UnlinkResult result = service.delete(trip.getId(), USER_ID, "dest-1");

        assertThat(result.checklistItemsUnlinked()).isZero();
        assertThat(result.itineraryItemsUnlinked()).isZero();
        assertThat(result.budgetItemsUnlinked()).isZero();
        assertThat(checklist.findAll(SLUG).get(0).getCountryCodes()).containsExactly("BO");
        assertThat(itinerary.findAll(SLUG).get(0).getCountryCodes()).containsExactly("BO");
        assertThat(budget.findAll(SLUG).get(0).getCountryCodes()).containsExactly("BO");
    }
}
