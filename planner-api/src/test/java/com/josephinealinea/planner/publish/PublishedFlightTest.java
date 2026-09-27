import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.api.SettlementProperties;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.notification.LoggingEmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.publish.api.PublishService;
import com.josephinealinea.planner.publish.api.StaticSiteRenderer;
import com.josephinealinea.planner.publish.api.PublishApprovalProperties;
import com.josephinealinea.planner.rates.TestRates;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;

/** A flight on a published page: what ships, and what must not. */
class PublishedFlightTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private PublishService publish;
    private TripViewAssembler views;
    private TripRepository trips;
    private UserRepository users;
    private Path publishDir;
    private YamlDestinationRepository destinationsRepo;
    private YamlItineraryRepository itineraryRepo;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(tempDir.toString()),
                new AppProperties.Publish(tempDir.resolve("published").toString(),
                        "http://localhost:8080/p"),
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
        publishDir = paths.publishDir();

        var destinations = destinationsRepo = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);
        var settlementPayments = new com.josephinealinea.planner.budget.infra.YamlSettlementPaymentRepository(
                store, paths, locks);
        this.itineraryRepo = itinerary;
        trips = new YamlTripRepository(store, paths, locks);
        users = new YamlUserRepository(store, paths, locks);

        users.save(account(ALEX, "alex@example.com", "alex"));
        users.save(account(SAM, "sam@example.com", "sam"));

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setDisplayCurrency("EUR");
        trip.setStartDate(LocalDate.of(2026, 10, 24));
        trip.setEndDate(LocalDate.of(2026, 10, 30));
        trip.getMembers().add(new TripMember(ALEX, TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, TripRole.MEMBER, ALEX));
        trips.save(trip);

        Destination cusco = new Destination();
        cusco.setId("cusco");
        cusco.setTripId(TRIP_ID);
        cusco.setName("Cusco");
        destinations.save(SLUG, cusco);

        // 900 split two ways is 450 each; 1234.56 is Sam's alone. Distinctive
        // numbers so a test can search the file for one and not find it.

        var access = new TripAccessService(trips);
        // Built with a payment repository, so the maths that would show a
        // payment on a page has every chance to: the renderer must still never
        // name one.
        var budgets = new BudgetService(budget, itinerary, destinations, users, access,
                new TripCountries(destinations), TestRates.empty(store, paths, props),
                SettlementProperties.off(), settlementPayments);
        var renderer = new StaticSiteRenderer(destinations, checklist, itinerary, budgets,
                new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props);
        views = new TripViewAssembler(users, destinations, checklist, itinerary, budgets,
                TestRates.empty(store, paths, props), new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props,
                PublishApprovalProperties.required());
        publish = new PublishService(trips, access, views, renderer,
                new UserService(users, new BCryptPasswordEncoder(), props),
                new LoggingEmailSender(), new MailTemplates(props, com.josephinealinea.planner.i18n.I18nConfig.standalone()),
                PublishApprovalProperties.required());
    }

    private static User account(String id, String email, String screenName) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setScreenName(screenName);
        // A page has a budget only when its member asks for one.
        
        return user;
    }


    private static Airport airport(String iata, String city, double lat) {
        return new Airport(iata, "E" + iata, city + " Airport", city, "EE", "Europe/Tallinn", lat, 24.8328);
    }

    private static final FlightSnapshot KL = new FlightSnapshot("KL2842", "BT857",
            airport("TLL", "Tallinn", 59.4133), airport("AMS", "Amsterdam", 52.3086), "1", "B");

    private void entry(String id, String description, FlightSnapshot flight, List<String> travellers) {
        ItineraryItem item = new ItineraryItem();
        item.setId(id);
        item.setTripId(TRIP_ID);
        item.setCategory(ChecklistCategory.TRANSPORTATION);
        item.setDescription(description);
        item.setStartAt(LocalDateTime.of(2026, 10, 25, 14, 20));
        item.setFlight(flight);
        item.setTravellerIds(travellers);
        itineraryRepo.save(SLUG, item);
    }

    private String tripPage() throws Exception {
        return Files.readString(publishDir.resolve(SLUG).resolve("index.html"));
    }

    private JsonNode payload(String html) throws Exception {
        String marker = "window.TRIP = ";
        int start = html.indexOf(marker) + marker.length();
        return new ObjectMapper().readTree(html.substring(start, html.indexOf(";</script>", start)));
    }

    private JsonNode firstEntry(String html) throws Exception {
        return payload(html).get("days").get(0).get("entries").get(0);
    }

    @Test
    void theFlightNumbersAndAirportsAreInThePublishedFile() throws Exception {
        entry("f1", "Fly home", KL, null);
        publish.publish(TRIP_ID, ALEX, "minima");

        JsonNode flight = firstEntry(tripPage()).get("flight");
        assertThat(flight.get("number").asText()).isEqualTo("KL2842");
        assertThat(flight.get("operatingNumber").asText()).isEqualTo("BT857");
        assertThat(flight.get("fromIata").asText()).isEqualTo("TLL");
        assertThat(flight.get("fromCity").asText()).isEqualTo("Tallinn");
        assertThat(flight.get("toIata").asText()).isEqualTo("AMS");
        assertThat(flight.get("toCity").asText()).isEqualTo("Amsterdam");
        assertThat(flight.get("terminalFrom").asText()).isEqualTo("1");
        assertThat(flight.get("terminalTo").asText()).isEqualTo("B");
    }

    @Test
    void coordinatesTravelOnlyInsideEachAirportsMapLink() throws Exception {
        entry("f1", "Fly home", KL, null);
        publish.publish(TRIP_ID, ALEX, "minima");

        String html = tripPage();
        JsonNode flight = firstEntry(html).get("flight");
        assertThat(html).contains("KL2842");
        // Nor the ICAO code, timezone or country: searched in the whole file. The
        // airport's name IS shipped, and so is its map link — same as a destination's.
        assertThat(html).doesNotContain("Europe/Tallinn").doesNotContain("ETLL").doesNotContain("EAMS");
        assertThat(flight.get("fromName").asText()).isEqualTo("Tallinn Airport");
        assertThat(flight.get("toName").asText()).isEqualTo("Amsterdam Airport");
        assertThat(flight.get("fromMapUrl").asText())
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=59.4133,24.8328");
        assertThat(flight.get("toMapUrl").asText())
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=52.3086,24.8328");
        // No bare coordinate field alongside the link, and no country code either.
        assertThat(flight.has("lat")).isFalse();
        assertThat(flight.has("countryCode")).isFalse();
    }

    @Test
    void aNumberOnlyFlightShipsWithNoAirports() throws Exception {
        entry("f1", "Fly home", new FlightSnapshot("KL2842", null, null, null, null, null), null);
        publish.publish(TRIP_ID, ALEX, "minima");

        JsonNode flight = firstEntry(tripPage()).get("flight");
        assertThat(flight.get("number").asText()).isEqualTo("KL2842");
        assertThat(flight.get("fromIata").isNull()).isTrue();
        assertThat(flight.get("toIata").isNull()).isTrue();
        assertThat(flight.get("fromCity").isNull()).isTrue();
        assertThat(flight.get("toCity").isNull()).isTrue();
    }

    @Test
    void anEntryWithNoFlightHasNoFlightKey() throws Exception {
        entry("t1", "Bus to Cusco", null, null);
        publish.publish(TRIP_ID, ALEX, "minima");

        JsonNode entry = firstEntry(tripPage());
        assertThat(entry.get("description").asText()).isEqualTo("Bus to Cusco");
        // Absent or null: no flight object either way.
        assertThat(entry.get("flight") == null || entry.get("flight").isNull()).isTrue();
    }

    /** The rule that "not displayed" must mean "not shipped". */
    @Test
    void anotherMembersFlightIsNotOnMyPersonalPage() throws Exception {
        entry("f1", "Sam's flight", KL, List.of(SAM));
        entry("t1", "Bus to Cusco", null, List.of(ALEX));
        publish.publish(TRIP_ID, ALEX, "minima");

        String alexPage = Files.readString(publishDir.resolve(SLUG).resolve("m").resolve("alex")
                .resolve("index.html"));
        String samPage = Files.readString(publishDir.resolve(SLUG).resolve("m").resolve("sam")
                .resolve("index.html"));
        assertThat(alexPage).contains("Bus to Cusco");
        assertThat(alexPage).doesNotContain("KL2842").doesNotContain("BT857").doesNotContain("Sam's flight");
        // Nor where it goes: the airport codes and cities, anywhere in the file.
        assertThat(alexPage).doesNotContain("TLL").doesNotContain("AMS")
                .doesNotContain("Tallinn").doesNotContain("Amsterdam");
        assertThat(samPage).contains("KL2842").contains("TLL").contains("Amsterdam"); // the needles are findable
    }

    @Test
    void noKeyOrProviderFieldIsInTheFile() throws Exception {
        entry("f1", "Fly home", KL, null);
        publish.publish(TRIP_ID, ALEX, "minima");

        String html = tripPage();
        assertThat(html).contains("KL2842");
        assertThat(html).doesNotContain("access_key").doesNotContain("X-RapidAPI")
                .doesNotContain("aerodatabox").doesNotContain("aviationstack");
        // The gate is only ever read live, from the API, never written into the file.
        // (page.js names these fields to draw a live reply, so only the data is searched.)
        assertThat(payload(html).toString()).doesNotContain("gate").doesNotContain("baggage");
    }
}
