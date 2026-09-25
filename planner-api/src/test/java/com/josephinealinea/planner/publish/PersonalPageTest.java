package com.josephinealinea.planner.publish;

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
 *   <li><b>every member of the trip gets one, without asking.</b> Publishing a
 *       trip publishes it for the people on it;</li>
 *   <li><b>a page must not outlive the membership it was written for.</b>
 *       Publishing again rewrites index.html in place, so somebody who has
 *       since left the trip would quietly keep serving their own copy of it;</li>
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
    private YamlDestinationRepository destinationsRepo;

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
        budget.save(SLUG, expense("shared", "Flight", "900.00", List.of(ALEX, SAM)));
        budget.save(SLUG, expense("sams", "Sam's camera", "1234.56", List.of(SAM)));

        // Distinctive amount and note, for the same reason: a test can search
        // the whole file for them.
        SettlementPayment payment = new SettlementPayment();
        payment.setId("pay-1");
        payment.setTripId(TRIP_ID);
        payment.setFromUserId(SAM);
        payment.setToUserId(ALEX);
        payment.setAmount(new java.math.BigDecimal("77.31"));
        payment.setCurrency("EUR");
        payment.setNote("Cash for the tuk-tuk 4471");
        settlementPayments.save(SLUG, payment);

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

    // ── helpers ─────────────────────────────────────────────────────────

    private static User account(String id, String email, String screenName) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setScreenName(screenName);
        // A page has a budget only when its member asks for one.
        user.getPublishedPage().setDisplayBudget(true);
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
        publish.publish(TRIP_ID, ALEX, "minima");

        JsonNode trip = payload(read(publishDir.resolve(SLUG).resolve("index.html")));
        JsonNode alex = payload(read(personal("alex")));

        // The trip's public page has no budget at all: that is for personal pages.
        assertThat(trip.get("budget").isNull()).isTrue();
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
        publish.publish(TRIP_ID, ALEX, "minima");

        String alexPage = read(personal("alex"));
        String tripPage = read(publishDir.resolve(SLUG).resolve("index.html"));

        // Sam's camera, and the trip-wide total that includes it, appear
        // nowhere in Alex's file — not in the markup, not in window.TRIP.
        assertThat(alexPage).doesNotContain("1234.56");
        assertThat(alexPage).doesNotContain("2134.56");
        // The trip's own page carries no budget, so nobody's spending is in it.
        assertThat(tripPage).doesNotContain("2134.56");
        assertThat(tripPage).doesNotContain("1234.56");
    }

    /**
     * Who's going decides what a personal page lists, and — as with the money
     * above — what it leaves out is left out of the file, not hidden in it.
     */
    @Test
    void aPersonalPageLeavesOutDestinationsTheBuddyIsNotGoingTo() throws Exception {
        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setTripId(TRIP_ID);
        uyuni.setName("Salar de Uyuni");
        uyuni.setCountryCode("BO");
        uyuni.setTravellerIds(List.of(SAM));
        destinationsRepo.save(SLUG, uyuni);

        publish.publish(TRIP_ID, ALEX, "minima");

        // Every page carries the whole code -> name table (public reference
        // data), so "Bolivia" is always in the file. What must be absent is the
        // destination's own link to it.
        assertThat(read(personal("alex"))).doesNotContain("Salar de Uyuni").doesNotContain("\"countryCode\":\"BO\"");
        assertThat(read(personal("sam"))).contains("Salar de Uyuni").contains("\"countryCode\":\"BO\"");
        assertThat(read(publishDir.resolve(SLUG).resolve("index.html"))).contains("Salar de Uyuni");
    }

    @Test
    void noBuddysIdReachesAPublishedFile() throws Exception {
        Destination uyuni = new Destination();
        uyuni.setId("uyuni");
        uyuni.setTripId(TRIP_ID);
        uyuni.setName("Salar de Uyuni");
        uyuni.setTravellerIds(List.of(SAM));
        destinationsRepo.save(SLUG, uyuni);

        publish.publish(TRIP_ID, ALEX, "minima");

        for (Path file : List.of(publishDir.resolve(SLUG).resolve("index.html"), personal("sam"))) {
            assertThat(read(file)).doesNotContain(SAM).doesNotContain(ALEX);
        }
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

    /**
     * A payment settles a debt, and a debt is between the people on the trip —
     * so neither the trip's page nor either member's may carry one, or its
     * amount, or its note. Same reasoning, same whole-file search.
     */
    @Test
    void noPaymentReachesAPublishedFile() throws Exception {
        publish.publish(TRIP_ID, ALEX, "minima");

        for (String file : new String[]{read(personal("alex")), read(personal("sam")),
                read(publishDir.resolve(SLUG).resolve("index.html"))}) {
            assertThat(file).doesNotContain("77.31");
            assertThat(file).doesNotContain("4471");
            assertThat(file).doesNotContain("tuk-tuk");
            assertThat(file).doesNotContain("pay-1");
            assertThat(file).doesNotContain("fromUserId");
            assertThat(file).doesNotContain("\"payments\"");
        }
    }

    @Test
    void eachMemberGetsTheirOwnPageAtTheirOwnName() throws Exception {
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(personal("alex")).exists();
        assertThat(personal("sam")).exists();

        assertThat(payload(read(personal("alex"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("450.00");
        // Sam's half of the flight plus his camera.
        assertThat(payload(read(personal("sam"))).get("budget").get("charged").get("total")
                .decimalValue()).isEqualByComparingTo("1684.56");
    }

    // ── everybody on the trip, nobody else ──────────────────────────────

    /**
     * Nobody asked for anything here: publishing a trip publishes it for the
     * people on it, each of them seeing their own share of it.
     */
    @Test
    void everyMemberGetsAPageWithoutAnyoneOptingIn() {
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(personal("alex")).exists();
        assertThat(personal("sam")).exists();
    }

    /**
     * Publishing again rewrites index.html in place, so without an explicit
     * clear-out somebody who has since left the trip would keep serving their
     * own copy of it at a URL nobody is watching any more.
     */
    @Test
    void leavingTheTripAndPublishingAgainTakesThatPageDown() {
        publish.publish(TRIP_ID, ALEX, "minima");
        assertThat(personal("sam")).exists();

        Trip trip = trips.findById(TRIP_ID).orElseThrow();
        trip.getMembers().removeIf(member -> SAM.equals(member.getUserId()));
        trips.save(trip);
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(Files.exists(personal("sam"))).isFalse();
        // The owner's page, and the trip's own, are untouched by any of that.
        assertThat(personal("alex")).exists();
        assertThat(publishDir.resolve(SLUG).resolve("index.html")).exists();
    }

    @Test
    void unpublishingTheTripTakesEveryPersonalPageWithIt() {
        publish.publish(TRIP_ID, ALEX, "minima");

        publish.unpublish(TRIP_ID, ALEX);

        assertThat(Files.exists(personal("alex"))).isFalse();
        assertThat(Files.exists(personal("sam"))).isFalse();
        assertThat(Files.exists(publishDir.resolve(SLUG))).isFalse();
    }

    // ── the link a member is offered ────────────────────────────────────

    @Test
    void eachMemberIsGivenTheUrlOfTheirOwnPageAndNobodyElses() {
        publish.publish(TRIP_ID, ALEX, "minima");
        Trip trip = trips.findById(TRIP_ID).orElseThrow();

        assertThat(views.publish(trip, ALEX).myPublicUrl())
                .isEqualTo("http://localhost:8080/p/" + SLUG + "/m/alex");
        assertThat(views.publish(trip, SAM).myPublicUrl())
                .isEqualTo("http://localhost:8080/p/" + SLUG + "/m/sam");
    }

    @Test
    void onlyTheOwnerIsOfferedEveryonesLinks() {
        publish.publish(TRIP_ID, ALEX, "minima");
        Trip trip = trips.findById(TRIP_ID).orElseThrow();

        // Alex's own page is offered as "my page", so the list is everyone else.
        assertThat(views.publish(trip, ALEX).personalPageUrls()).containsExactly(
                "http://localhost:8080/p/" + SLUG + "/m/sam");
        assertThat(views.publish(trip, SAM).personalPageUrls()).isEmpty();
    }

    /**
     * Checked against the file rather than against the membership: offering a
     * link to a page that has not been written yet hands somebody a 404 to
     * share.
     */
    @Test
    void noLinkIsOfferedUntilTheTripHasActuallyBeenPublished() {
        Trip draft = trips.findById(TRIP_ID).orElseThrow();

        assertThat(Files.exists(personal("alex"))).isFalse();
        assertThat(views.publish(draft, ALEX).myPublicUrl()).isNull();

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
        publish.publish(TRIP_ID, ALEX, "minima");

        assertThat(payload(read(personal("alex")))
                .get("budget").get("displayCurrency").asText()).isEqualTo("EUR");
        assertThat(payload(read(personal("sam")))
                .get("budget").get("displayCurrency").asText()).isEqualTo("SGD");
    }

    // ── a request stages personal pages too ─────────────────────────────

    @Test
    void aRequestStagesThePersonalPagesAndApprovingMovesThemIntoPlace() {
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
