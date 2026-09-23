package com.josephinealinea.planner.trips;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.publish.infra.FileSystemPageStore;
import com.josephinealinea.planner.publish.api.PublishApprovalProperties;
import com.josephinealinea.planner.rates.TestRates;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.api.TripViews;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The server decides what is "mine"; the page only filters on it. See the travellers spec, section 3. */
class TravellersViewTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private TripViewAssembler views;
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
        var store = new YamlStore();
        var paths = new YamlPaths(props);
        var locks = new TripLocks();
        var destinations = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);
        var trips = new YamlTripRepository(store, paths, locks);
        var users = new YamlUserRepository(store, paths, locks);
        for (String id : List.of(ALEX, SAM)) {
            User u = new User();
            u.setId(id);
            u.setEmail(id + "@example.com");
            users.save(u);
        }

        trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setDisplayCurrency("EUR");
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setName("Uyuni");
        uyuni.setTravellerIds(List.of(SAM));
        destinations.save(SLUG, uyuni);

        ChecklistItem tour = new ChecklistItem();
        tour.setId("uyuni-tour");
        tour.setDescription("Book the salt flats tour");
        tour.setSeededFromDestinationId("uyuni");
        tour.setStatus(ChecklistStatus.COMPLETED);
        ChecklistItem visas = new ChecklistItem();
        visas.setId("visas");
        visas.setDescription("Visas");
        visas.setStatus(ChecklistStatus.TODO);
        checklist.save(SLUG, tour);
        checklist.save(SLUG, visas);

        var access = new TripAccessService(trips);
        var budgets = new BudgetService(budget, itinerary, destinations, users, access,
                new TripCountries(destinations), TestRates.empty(store, paths, props));
        views = new TripViewAssembler(users, destinations, checklist, itinerary, budgets,
                TestRates.empty(store, paths, props), new FileSystemPageStore(store, paths), props,
                PublishApprovalProperties.required());
    }

    @Test
    void eachBuddyIsToldWhichRecordsAreTheirs() {
        TripViews.TripDetail alex = views.detail(trip, ALEX);
        TripViews.TripDetail sam = views.detail(trip, SAM);

        assertThat(alex.mine().destinationIds()).isEmpty();
        assertThat(alex.mine().checklistItemIds()).containsExactly("visas");
        assertThat(sam.mine().destinationIds()).containsExactly("uyuni");
        assertThat(sam.mine().checklistItemIds()).containsExactlyInAnyOrder("uyuni-tour", "visas");
        // Everything is still sent: focus view, not privacy.
        assertThat(alex.destinations()).extracting(Destination::getId).containsExactly("uyuni");
    }

    @Test
    void namesAreSentOnlyForWhatIsNotTheWholeTrip() {
        TripViews.TripDetail alex = views.detail(trip, ALEX);

        assertThat(alex.travellers().destinations()).containsOnlyKeys("uyuni");
        assertThat(alex.travellers().checklist()).containsOnlyKeys("uyuni-tour");
        assertThat(alex.travellers().checklist().get("uyuni-tour")).containsExactly(SAM);
    }

    @Test
    void myTripsCountsTheBuddysOwnChecklist() {
        assertThat(views.summary(trip, ALEX).checklistTotal()).isEqualTo(1);
        assertThat(views.summary(trip, ALEX).checklistCompleted()).isZero();
        assertThat(views.summary(trip, SAM).checklistTotal()).isEqualTo(2);
        assertThat(views.summary(trip, SAM).checklistCompleted()).isEqualTo(1);
    }
}
