package com.josephinealinea.planner.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.budget.api.BudgetService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member's own published page: the same trip, with their share of each
 * expense instead of the trip's whole spend.
 *
 * This is the only answer a static page can give to "show me only my budget".
 * There is no sign-in and no API behind the file, so whose money it shows has
 * to be decided when it is <i>written</i>, not when it is read — which makes
 * every property here a privacy property rather than a display one:
 *
 * <ul>
 *   <li><b>another member's figures must not be in the file at all.</b>
 *       Filtering in page.js would be theatre: anyone can open the source and
 *       read window.TRIP. The assertions below therefore search the whole
 *       rendered file for the other person's number, not the markup;</li>
 *   <li><b>a member who did not ask for a page must not have one.</b> Off has
 *       to mean no file anywhere, because the URL is public;</li>
 *   <li><b>un-ticking the box must actually take the page down.</b> Publishing
 *       again rewrites index.html in place, so a stale personal page would
 *       quietly keep serving at a URL its owner believes they turned off;</li>
 *   <li><b>a personal page must not outlive its trip.</b> The same cascade the
 *       trip page has, and the reason these are nested inside the trip's own
 *       directory rather than sitting beside it.</li>
 * </ul>
 */
class PersonalPageTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private PublishService publish;
    private TripViewAssembler views;
    private TripRepository trips;
    private UserRepository users;
    private Path publishDir;

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

        var destinations = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);
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
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        Destination cusco = new Destination();
        cusco.setId("cusco");
        cusco.setTripId(TRIP_ID);
        cusco.setName("Cusco");
        cusco.setCountryName("Peru");
        destinations.save(SLUG, cusco);

        // 900 split two ways is 450 each; 1234.56 is Sam's alone. Distinctive
        // numbers so a test can search the file for one and not find it.
        budget.save(SLUG, expense("shared", "Flight", "900.00", List.of(ALEX, SAM)));
        budget.save(SLUG, expense("sams", "Sam's camera", "1234.56", List.of(SAM)));

        var access = new TripAccessService(trips);
        var budgets = new BudgetService(budget, itinerary, destinations, users, access,
                new TripCountries(destinations), TestRates.empty(store, paths, props));
        var renderer = new StaticSiteRenderer(destinations, checklist, itinerary, budgets,
                new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths));
        views = new TripViewAssembler(users, destinations, checklist, itinerary, budgets,
                TestRates.empty(store, paths, props), new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props);
        publish = new PublishService(trips, access, views, renderer,
                new UserService(users, new BCryptPasswordEncoder(), props),
                new LoggingEmailSender(), new MailTemplates(props));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static User account(String id, String email, String screenName) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setScreenName(screenName);
        return user;
    }

    private static BudgetItem expense(String id, String description, String amount,
                                      List<String> sharers) {
        BudgetItem item = new BudgetItem();
        item.setId(id);
        item.setTripId(TRIP_ID);
        item.setCategory(ChecklistCategory.OTHERS);
        item.setDescription(description);
        item.setAmount(new BigDecimal(amount));
        item.setCurrency("EUR");
        item.setSharedByUserIds(sharers);
        return item;
    }

    /** Turns a member's personal page on and saves it. */
    private void wantsOwnPage(String userId) {
        User user = users.findById(userId).orElseThrow();
        user.setPublishPersonalBudget(true);
        users.save(user);
    }

    private Path personal(String memberSlug) {
        return publishDir.resolve(SLUG).resolve("m").resolve(memberSlug).resolve("index.html");
    }

    private String read(Path file) throws Exception {
        return Files.readString(file);
    }

    /** The window.TRIP payload a rendered page carries, as JSON. */
    private JsonNode payload(String html) throws Exception {
        String marker = "window.TRIP = ";
        int start = html.indexOf(marker) + marker.length();
        return new ObjectMapper().readTree(html.substring(start, html.indexOf(";</script>", start)));
    }

    // ── whose money the page shows ──────────────────────────────────────

    @Test
    void aPersonalPageShowsThatMembersShareRatherThanTheTripsSpend() throws Exception {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");

        JsonNode trip = payload(read(publishDir.resolve(SLUG).resolve("index.html")));
        JsonNode alex = payload(read(personal("alex")));

        // The trip page counts everything: 900 + 1234.56.
        assertThat(trip.get("budget").get("charged").get("total").decimalValue())
                .isEqualByComparingTo("2134.56");
        // Alex's page counts his half of the flight, and nothing else.
        assertThat(alex.get("budget").get("charged").get("total").decimalValue())
                .isEqualByComparingTo("450.00");
    }

    /**
     * The one that would be theatre if it were done in the page. A value left
     * in window.TRIP is readable by anyone who opens the source, so the test
     * searches the whole file rather than the markup.
     */
    @Test
    void anotherMembersSpendingIsNowhereInThePersonalPageFile() throws Exception {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");

        String alexPage = read(personal("alex"));
        String tripPage = read(publishDir.resolve(SLUG).resolve("index.html"));

        // Sam's camera, and the trip-wide total that includes it, appear
        // nowhere in Alex's file — not in the markup, not in window.TRIP.
        assertThat(alexPage).doesNotContain("1234.56");
        assertThat(alexPage).doesNotContain("2134.56");
        // The trip's own page is the opposite case and is meant to carry it.
        assertThat(tripPage).contains("2134.56");
    }

    /**
     * A personal page is the one published file rendered <i>for</i> a signed-in
     * member, so it is the one place settlements are computed at publish time —
     * BudgetService fills them in for any viewer it is given. The renderer
     * builds its payload by hand and must never start reading them: who owes
     * whom names other members beside figures, which is precisely what a public
     * file cannot carry.
     *
     * Asserted over the whole file rather than the markup, for the reason the
     * test above gives — anyone can open the source and read window.TRIP.
     */
    @Test
    void noSettlementReachesAPublishedFile() throws Exception {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");

        String alexPage = read(personal("alex"));
        String tripPage = read(publishDir.resolve(SLUG).resolve("index.html"));

        for (String file : new String[]{alexPage, tripPage}) {
            assertThat(file).doesNotContain("settlements");
            assertThat(file).doesNotContain("owesYou");
            assertThat(file).doesNotContain("youOwe");
        }
        assertThat(payload(alexPage).has("settlements")).isFalse();
        assertThat(payload(alexPage).get("budget").has("settlements")).isFalse();
    }

    @Test
    void eachMemberGetsTheirOwnPageAtTheirOwnName() throws Exception {
        wantsOwnPage(ALEX);
        wantsOwnPage(SAM);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(personal("alex")).exists();
        assertThat(personal("sam")).exists();

        assertThat(payload(read(personal("alex"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("450.00");
        // Sam's half of the flight plus his camera.
        assertThat(payload(read(personal("sam"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("1684.56");
    }

    // ── off has to mean no file ─────────────────────────────────────────

    @Test
    void aMemberWhoDidNotAskForAPageDoesNotGetOne() {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(personal("alex")).exists();
        assertThat(Files.exists(personal("sam"))).isFalse();
    }

    @Test
    void publishingWithNobodyOptedInWritesNoPersonalDirectoryAtAll() {
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(publishDir.resolve(SLUG).resolve("index.html")).exists();
        assertThat(Files.exists(publishDir.resolve(SLUG).resolve("m"))).isFalse();
    }

    /**
     * Publishing again rewrites index.html in place, so without an explicit
     * clear-out a page somebody has since turned off would keep serving.
     */
    @Test
    void untickingTheBoxAndPublishingAgainTakesThePageDown() {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");
        assertThat(personal("alex")).exists();

        User alex = users.findById(ALEX).orElseThrow();
        alex.setPublishPersonalBudget(false);
        users.save(alex);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(Files.exists(personal("alex"))).isFalse();
        // The trip's own page is untouched by any of that.
        assertThat(publishDir.resolve(SLUG).resolve("index.html")).exists();
    }

    @Test
    void unpublishingTheTripTakesEveryPersonalPageWithIt() {
        wantsOwnPage(ALEX);
        wantsOwnPage(SAM);
        publish.publish(TRIP_ID, ALEX, "minima");

        publish.unpublish(TRIP_ID, ALEX);

        assertThat(Files.exists(personal("alex"))).isFalse();
        assertThat(Files.exists(personal("sam"))).isFalse();
        assertThat(Files.exists(publishDir.resolve(SLUG))).isFalse();
    }

    // ── the link a member is offered ────────────────────────────────────

    @Test
    void aMemberIsGivenTheUrlOfTheirOwnPageAndNobodyElses() {
        wantsOwnPage(ALEX);
        publish.publish(TRIP_ID, ALEX, "minima");
        Trip trip = trips.findById(TRIP_ID).orElseThrow();

        assertThat(views.publish(trip, ALEX).myPublicUrl())
                .isEqualTo("http://localhost:8080/p/" + SLUG + "/m/alex");
        // Sam did not ask for one, so there is nothing to offer him.
        assertThat(views.publish(trip, SAM).myPublicUrl()).isNull();
    }

    /**
     * The setting takes effect on the next publish, like every other
     * published-page setting. Offering the link in between would hand somebody
     * a 404 to share, so it is checked against the file rather than the flag.
     */
    @Test
    void noLinkIsOfferedUntilTheTripHasActuallyBeenPublishedWithTheBoxOn() {
        publish.publish(TRIP_ID, ALEX, "minima");
        wantsOwnPage(ALEX);
        Trip trip = trips.findById(TRIP_ID).orElseThrow();

        assertThat(Files.exists(personal("alex"))).isFalse();
        assertThat(views.publish(trip, ALEX).myPublicUrl()).isNull();

        publish.publish(TRIP_ID, ALEX, "minima");
        assertThat(views.publish(trips.findById(TRIP_ID).orElseThrow(), ALEX).myPublicUrl())
                .isNotNull();
    }

    // ── two members with the same name ──────────────────────────────────

    /**
     * Without the de-duplication the second would write into the first's
     * directory and silently replace their page — with their finances.
     */
    @Test
    void twoMembersWithTheSameDisplayNameGetSeparateDirectories() throws Exception {
        User sam = users.findById(SAM).orElseThrow();
        sam.setScreenName("alex");
        users.save(sam);
        wantsOwnPage(ALEX);
        wantsOwnPage(SAM);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(personal("alex")).exists();
        assertThat(personal("alex-2")).exists();
        assertThat(payload(read(personal("alex"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("450.00");
        assertThat(payload(read(personal("alex-2"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("1684.56");
    }

    /**
     * The trip's page and a personal one label their money differently because
     * they count it differently: a member's totals are converted into whatever
     * display currency that member keeps, so the label has to follow them. It
     * used to be the trip's own anchor, which was right only for as long as
     * nobody but the trip was ever summarised.
     */
    @Test
    void aPersonalPageIsLabelledWithThatMembersOwnDisplayCurrency() throws Exception {
        User sam = users.findById(SAM).orElseThrow();
        sam.setDisplayCurrency("SGD");
        users.save(sam);
        wantsOwnPage(SAM);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(payload(read(publishDir.resolve(SLUG).resolve("index.html")))
                .get("budget").get("displayCurrency").asText()).isEqualTo("EUR");
        assertThat(payload(read(personal("sam")))
                .get("budget").get("displayCurrency").asText()).isEqualTo("SGD");
    }

    // ── a request stages personal pages too ─────────────────────────────

    @Test
    void aRequestStagesThePersonalPagesAndApprovingMovesThemIntoPlace() {
        wantsOwnPage(SAM);
        publish.requestPublish(TRIP_ID, SAM, "ready?", "minima");

        assertThat(Files.exists(personal("sam"))).isFalse();

        Trip trip = trips.findById(TRIP_ID).orElseThrow();
        String requestId = trip.getPublishRequests().get(0).getId();
        publish.approveRequest(TRIP_ID, ALEX, requestId, "minima");

        assertThat(personal("sam")).exists();
    }

    // ── the whole set, as a map ─────────────────────────────────────────

    @Test
    void theSlugsAMemberIsGivenAreTheOnesTheFilesAreWrittenUnder() {
        wantsOwnPage(ALEX);
        wantsOwnPage(SAM);
        Trip trip = trips.findById(TRIP_ID).orElseThrow();

        Map<String, String> slugs = com.josephinealinea.planner.publish.api.PersonalPages
                .slugsFor(trip, Map.of(
                        ALEX, users.findById(ALEX).orElseThrow(),
                        SAM, users.findById(SAM).orElseThrow()));

        assertThat(slugs).containsExactly(
                org.assertj.core.data.MapEntry.entry(ALEX, "alex"),
                org.assertj.core.data.MapEntry.entry(SAM, "sam"));
    }
}
