package com.josephinealinea.planner.publish;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
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
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requesting a publish, and what the owner's decision does with it.
 *
 * The shape being pinned down: a member who is not the owner cannot publish,
 * but their request **builds the page immediately** — with their own theme and
 * their own account settings — into a staging directory. Approving moves that
 * file into place untouched, so what goes public is what they asked to publish
 * rather than a rebuild using whatever the owner has configured.
 *
 * Two properties matter more than the mechanics:
 *
 * <ul>
 *   <li><b>Nothing unapproved is in the public directory.</b> That directory
 *       means "everything here is public" — it is what gets copied to a CDN —
 *       so a staged page living there, however well hidden by the UI, would be
 *       a leak waiting for somebody to guess a slug. Slugs come from trip
 *       titles, so they are guessable.</li>
 *   <li><b>No URL exists until approval</b>, because publicUrl answers null
 *       for a trip that is not published.</li>
 * </ul>
 */
class PublishApprovalTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String OWNER = "user-owner";
    private static final String MEMBER = "user-member";

    private PublishService publish;
    private TripRepository trips;
    private YamlUserRepository users;
    private TripViewAssembler views;
    private Path publishDir;
    private Path pendingDir;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(),
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
        pendingDir = paths.pendingDir();

        var destinations = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);
        users = new YamlUserRepository(store, paths, locks);

        // The requesting member wants costs shown; the owner does not. That is
        // the difference every assertion below turns on.
        users.save(account(OWNER, "owner@example.com", false));
        users.save(account(MEMBER, "member@example.com", true));

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(OWNER);
        trip.setDisplayCurrency("EUR");
        trip.getMembers().add(new TripMember(OWNER, "owner@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(MEMBER, "member@example.com", TripRole.MEMBER, OWNER));
        trips.save(trip);

        Destination cusco = new Destination();
        cusco.setId("cusco");
        cusco.setTripId(TRIP_ID);
        cusco.setName("Cusco");
        cusco.setCountryName("Peru");
        cusco.setStartDate(LocalDate.of(2026, 10, 25));
        cusco.setEndDate(LocalDate.of(2026, 10, 31));
        destinations.save(SLUG, cusco);

        ItineraryItem hotel = new ItineraryItem();
        hotel.setId("hotel");
        hotel.setTripId(TRIP_ID);
        hotel.setCategory(ChecklistCategory.LODGING);
        hotel.setDescription("Hotel in Cusco");
        hotel.setStartAt(LocalDateTime.of(2026, 10, 25, 15, 0));
        hotel.setCost(new BigDecimal("240.00"));
        hotel.setCurrency("EUR");
        itinerary.save(SLUG, hotel);

        var access = new TripAccessService(trips);
        var budgets = new BudgetService(budget, itinerary, destinations, users, access,
                new TripCountries(destinations), TestRates.empty(store, paths, props));
        var renderer = new StaticSiteRenderer(destinations, checklist, itinerary, budgets,
                new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props);
        views = new TripViewAssembler(users, destinations, checklist, itinerary, budgets,
                TestRates.empty(store, paths, props), new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props);
        publish = new PublishService(trips, access, views, renderer,
                new UserService(users, new BCryptPasswordEncoder(), props),
                new LoggingEmailSender(), new MailTemplates(props));
    }

    private static User account(String id, String email, boolean showCosts) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPublishItineraryCost(showCosts);
        return user;
    }

    private Path staged() { return pendingDir.resolve(SLUG).resolve("index.html"); }
    private Path live()   { return publishDir.resolve(SLUG).resolve("index.html"); }

    // ── requesting ──────────────────────────────────

    @Test
    void aRequestBuildsThePageButNothingPublic() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, "ready?", "y2k");

        assertThat(staged()).exists();
        // The public directory means "everything here is public".
        assertThat(Files.exists(live())).isFalse();
        assertThat(trips.findById(TRIP_ID).orElseThrow().getStatus()).isEqualTo(TripStatus.DRAFT);
    }

    @Test
    void noUrlExistsWhileTheRequestIsUndecided() {
        Trip trip = publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        assertThat(views.publicUrl(trip)).isNull();
        assertThat(views.publish(trip, MEMBER).publicUrl()).isNull();
    }

    /** The requester's settings, not the owner's — the point of the change. */
    @Test
    void theStagedPageUsesTheRequestersOwnSettingsAndTheme() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        String html = Files.readString(staged());
        assertThat(html).contains("data-theme=\"y2k\"");
        // The member has costs switched on; the owner has them off.
        assertThat(html).contains("240.00");
    }

    // ── approving ───────────────────────────────────

    @Test
    void approvingMovesTheStagedPageIntoPlaceUntouched() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");
        String asStaged = Files.readString(staged());
        String requestId = pendingRequestId();

        Trip published = publish.approveRequest(TRIP_ID, OWNER, requestId, "minima");

        assertThat(Files.readString(live())).isEqualTo(asStaged);
        // Nothing left behind to go live a second time.
        assertThat(Files.exists(staged())).isFalse();
        assertThat(published.getStatus()).isEqualTo(TripStatus.PUBLISHED);
        assertThat(views.publicUrl(published)).isEqualTo("http://localhost:8080/p/" + SLUG);
    }

    /**
     * The approving owner's own theme and settings are deliberately ignored:
     * they are approving somebody else's page, not publishing their own.
     */
    @Test
    void theOwnersThemeAndSettingsDoNotOverrideTheRequesters() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        publish.approveRequest(TRIP_ID, OWNER, pendingRequestId(), "minima");

        String live = Files.readString(live());
        assertThat(live).contains("data-theme=\"y2k\"");
        assertThat(live).contains("240.00");
        assertThat(trips.findById(TRIP_ID).orElseThrow().getPublishedTheme()).isEqualTo("y2k");
    }

    /** Changes made after the request are not picked up: the page is a snapshot. */
    @Test
    void approvingPublishesTheSnapshotTakenWhenTheRequestWasSent() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        Trip trip = trips.findById(TRIP_ID).orElseThrow();
        trip.setTitle("Renamed after the request");
        trips.save(trip);

        publish.approveRequest(TRIP_ID, OWNER, pendingRequestId(), null);

        assertThat(Files.readString(live()))
                .contains("LATAM Trip 2026")
                .doesNotContain("Renamed after the request");
    }

    // ── refusing, and cleaning up ───────────────────

    @Test
    void rejectingThrowsTheStagedPageAway() {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");
        assertThat(staged()).exists();

        publish.rejectRequest(TRIP_ID, OWNER, pendingRequestId());

        assertThat(Files.exists(staged())).isFalse();
        assertThat(Files.exists(live())).isFalse();
    }

    @Test
    void withdrawingTheRequestThrowsTheStagedPageAway() {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        publish.cancelRequest(TRIP_ID, MEMBER, pendingRequestId());

        assertThat(Files.exists(staged())).isFalse();
    }

    /**
     * The owner publishing directly supersedes the staged page, which must not
     * be left behind to be promoted by a later approval.
     */
    @Test
    void theOwnerPublishingDirectlyClearsAnythingStaged() throws Exception {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        publish.publish(TRIP_ID, OWNER, "minima");

        assertThat(Files.exists(staged())).isFalse();
        String live = Files.readString(live());
        assertThat(live).contains("data-theme=\"minima\"");
        // The owner has costs off, and this is the owner's own publish.
        assertThat(live).doesNotContain("240.00");
    }

    // ── preview ─────────────────────────────────────

    @Test
    void aMemberCanPreviewTheStagedPageBeforeItIsApproved() {
        publish.requestPublish(TRIP_ID, MEMBER, null, "y2k");

        assertThat(publish.previewPending(TRIP_ID, OWNER)).contains("data-theme=\"y2k\"");
        assertThat(publish.previewPending(TRIP_ID, MEMBER)).isNotNull();
    }

    @Test
    void thereIsNothingToPreviewWithNoPendingRequest() {
        assertThat(publish.previewPending(TRIP_ID, OWNER)).isNull();
    }

    private String pendingRequestId() {
        return trips.findById(TRIP_ID).orElseThrow().getPublishRequests().stream()
                .filter(request -> request.isPending())
                .findFirst().orElseThrow().getId();
    }
}
