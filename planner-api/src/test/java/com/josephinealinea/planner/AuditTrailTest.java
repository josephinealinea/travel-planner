package com.josephinealinea.planner;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.notification.LoggingEmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.publish.api.StaticSiteRenderer;
import com.josephinealinea.planner.rates.TestRates;
import com.josephinealinea.planner.shared.Audited;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who made each record, and who changed it last.
 *
 * This exists to be asked after the fact — a cost that looks wrong, a checklist
 * item nobody admits to renaming — so the properties worth pinning down are
 * about honesty rather than coverage:
 *
 * <ul>
 *   <li>a record is stamped with its author on creation, and carries no update
 *       stamp at all until something is actually changed;</li>
 *   <li>an edit by another member records that member without rewriting who
 *       created it;</li>
 *   <li>the records the app writes for you — the seeded checklist items, the
 *       nights a stay spreads over, the budget row a plan's cost creates — are
 *       attributed to the member whose action produced them, since otherwise
 *       the only rows with no author would be exactly the automatic ones;</li>
 *   <li>a cascade that rewrites a whole file stamps only the rows it really
 *       changed. This is the one that would quietly go wrong: unlinking three
 *       items out of fifteen must not read as fifteen edits.</li>
 * </ul>
 */
class AuditTrailTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private DestinationService destinationService;
    private ChecklistService checklistService;
    private ItineraryService itineraryService;
    private BudgetService budgetService;
    private TripService tripService;
    private TripRepository trips;
    private DestinationRepository destinations;
    private ChecklistRepository checklist;
    private ItineraryRepository itinerary;
    private BudgetRepository budget;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dir = tempDir;
        AppProperties props = new AppProperties(
                new AppProperties.Storage(tempDir.toString()),
                new AppProperties.Publish(tempDir.resolve("published").toString(), "http://localhost:8080/p"),
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
        checklist = new ChecklistRepository(store, paths, locks);
        itinerary = new ItineraryRepository(store, paths, locks);
        budget = new BudgetRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);
        UserRepository users = new YamlUserRepository(store, paths, locks);
        users.save(account(ALEX, "alex@example.com"));
        users.save(account(SAM, "sam@example.com"));

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setStartDate(LocalDate.parse("2026-10-20"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        TripCountries tripCountries = new TripCountries(destinations);
        CountryCatalog countries = new CountryCatalog(RestClient.create());

        destinationService = new DestinationService(destinations, checklist, itinerary, budget,
                new ChecklistSeeder(), access, countries, tripCountries);
        checklistService = new ChecklistService(checklist, itinerary, access, tripCountries);
        itineraryService = new ItineraryService(itinerary, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budget), countries, tripCountries);
        var testRates = TestRates.empty(store, paths, props);
        budgetService = new BudgetService(budget, itinerary, destinations, users, access, tripCountries, testRates);
        tripService = new TripService(trips, access,
                new UserService(users, new BCryptPasswordEncoder(), props),
                new LoggingEmailSender(), new MailTemplates(props),
                new StaticSiteRenderer(destinations, checklist, itinerary,
                        new BudgetService(budget, itinerary, destinations, users, access,
                                tripCountries, testRates),
                        store, paths));
    }

    // ── helpers ─────────────────────────────────────

    /** Both members need real accounts: addMember emails the inviter by name. */
    private static User account(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private DestinationService.Input place(String name, String code, String start, String end) {
        return new DestinationService.Input(name, code, null, null, null, null, null, start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, null);
    }

    /** Asserts a record was made by one member and has not been edited since. */
    private static void madeBy(Audited record, String userId) {
        assertThat(record.getCreatedByUserId()).isEqualTo(userId);
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedByUserId()).isNull();
        assertThat(record.getUpdatedAt()).isNull();
    }

    /** Asserts a record was made by one member and last changed by another. */
    private static void madeByThenChangedBy(Audited record, String author, String editor) {
        assertThat(record.getCreatedByUserId()).isEqualTo(author);
        assertThat(record.getUpdatedByUserId()).isEqualTo(editor);
        assertThat(record.getUpdatedAt()).isNotNull();
    }

    private ChecklistItem reloadChecklist(String id) {
        return checklist.findById(SLUG, id).orElseThrow();
    }

    // ── creation ────────────────────────────────────

    @Test
    void aDestinationRecordsWhoAddedIt() {
        Destination created = destinationService
                .create(TRIP_ID, ALEX, place("Cusco", "PE", "2026-10-25", "2026-10-31"))
                .destination();

        madeBy(destinations.findById(SLUG, created.getId()).orElseThrow(), ALEX);
    }

    @Test
    void theSeededChecklistItemsBelongToWhoeverAddedTheDestination() {
        destinationService.create(TRIP_ID, SAM, place("Cusco", "PE", "2026-10-25", "2026-10-31"));

        assertThat(checklist.findAll(SLUG))
                .hasSize(3)
                .allSatisfy(item -> madeBy(item, SAM));
    }

    @Test
    void aChecklistItemAddedByHandRecordsItsAuthor() {
        ChecklistItem item = checklistService.create(TRIP_ID, SAM,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Buy a padlock", null, null));

        madeBy(reloadChecklist(item.getId()), SAM);
    }

    @Test
    void anExpenseRecordsWhoEnteredIt() {
        BudgetItem row = budgetService.create(TRIP_ID, SAM,
                new BudgetService.Input("Yellow fever vaccine", ChecklistCategory.OTHERS,
                        new BigDecimal("45.00"), "USD", null, null, null, null, null));

        madeBy(budget.findById(SLUG, row.getId()).orElseThrow(), SAM);
    }

    // ── the records the app writes for you ──────────

    /**
     * The one that would otherwise have no author at all: nobody typed this row
     * into the budget, it appeared because a plan carried a cost.
     */
    @Test
    void theBudgetRowAPlanCostCreatesBelongsToThePlansAuthor() {
        itineraryService.create(TRIP_ID, SAM, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel in Cusco", LocalDateTime.parse("2026-10-25T15:00"), null, null, new BigDecimal("240.00"), "USD", null, null, null, null));

        assertThat(budget.findAll(SLUG)).singleElement().satisfies(row -> madeBy(row, SAM));
    }

    @Test
    void everyNightAStaySpreadsOverBelongsToThePlansAuthor() {
        itineraryService.create(TRIP_ID, SAM, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel in Cusco", LocalDateTime.parse("2026-10-25T15:00"), LocalDateTime.parse("2026-10-28T10:00"), null, null, null, null, null, null, null));

        // The plan's own row plus one per further day, all of them Sam's.
        assertThat(itinerary.findAll(SLUG)).hasSize(4).allSatisfy(day -> madeBy(day, SAM));
    }

    /**
     * The accommodation item seeded when dates arrive later belongs to whoever's
     * edit produced it — not to whoever added the destination back when it had
     * no dates and so needed no room.
     */
    @Test
    void aLateSeededLodgingItemBelongsToTheMemberWhoseEditProducedIt() {
        Destination created = destinationService
                .create(TRIP_ID, ALEX, place("Cusco", "PE", null, null))
                .destination();

        destinationService.update(TRIP_ID, SAM, created.getId(),
                place(null, null, "2026-10-25", "2026-10-31"));

        ChecklistItem lodging = checklist.findAll(SLUG).stream()
                .filter(item -> item.getCategory() == ChecklistCategory.LODGING)
                .findFirst().orElseThrow();
        madeBy(lodging, SAM);

        // And the two seeded up front are still Alex's, untouched.
        assertThat(checklist.findAll(SLUG).stream()
                .filter(item -> item.getCategory() != ChecklistCategory.LODGING))
                .allSatisfy(item -> madeBy(item, ALEX));
    }

    // ── editing ─────────────────────────────────────

    @Test
    void anEditRecordsTheEditorWithoutRewritingTheAuthor() {
        ChecklistItem item = checklistService.create(TRIP_ID, ALEX,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Buy a padlock", null, null));

        checklistService.update(TRIP_ID, SAM, item.getId(),
                new ChecklistService.Input(null, "Buy two padlocks", null, null));

        madeByThenChangedBy(reloadChecklist(item.getId()), ALEX, SAM);
    }

    @Test
    void completingAnItemIsAnEdit() {
        ChecklistItem item = checklistService.create(TRIP_ID, ALEX,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Buy a padlock", null, null));

        checklistService.setStatus(TRIP_ID, SAM, item.getId(), ChecklistStatus.COMPLETED);

        madeByThenChangedBy(reloadChecklist(item.getId()), ALEX, SAM);
    }

    @Test
    void editingADestinationRecordsTheEditor() {
        Destination created = destinationService
                .create(TRIP_ID, ALEX, place("Cusco", "PE", "2026-10-25", "2026-10-31"))
                .destination();

        destinationService.update(TRIP_ID, SAM, created.getId(), place("Cuzco", null, null, null));

        madeByThenChangedBy(destinations.findById(SLUG, created.getId()).orElseThrow(), ALEX, SAM);
    }

    @Test
    void correctingAnAmountInTheBudgetStampsThePlanItPushesBackTo() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel in Cusco", LocalDateTime.parse("2026-10-25T15:00"), null, null, new BigDecimal("240.00"), "USD", null, null, null, null));
        BudgetItem row = budget.findAll(SLUG).get(0);

        budgetService.update(TRIP_ID, SAM, row.getId(), new BudgetService.Input(
                null, null, new BigDecimal("260.00"), null, null, null, null, null, null));

        madeByThenChangedBy(budget.findById(SLUG, row.getId()).orElseThrow(), ALEX, SAM);
        madeByThenChangedBy(itinerary.findById(SLUG, plan.getId()).orElseThrow(), ALEX, SAM);
    }

    // ── cascades ────────────────────────────────────

    /**
     * The honesty test. Deleting a country's last destination unlinks the items
     * filed under it by rewriting the whole checklist file; the rows that kept
     * their links must come back out of it with no edit recorded against them.
     */
    @Test
    void anUnlinkCascadeStampsOnlyTheRowsThatReallyLostALink() {
        Destination cusco = destinationService
                .create(TRIP_ID, ALEX, place("Cusco", "PE", "2026-10-25", "2026-10-31"))
                .destination();
        destinationService.create(TRIP_ID, ALEX, place("Uyuni", "BO", "2026-11-01", "2026-11-03"));

        List<ChecklistItem> before = checklist.findAll(SLUG);
        List<String> peruvian = before.stream()
                .filter(item -> item.getCountryCodes().contains("PE"))
                .map(ChecklistItem::getId).toList();
        List<String> bolivian = before.stream()
                .filter(item -> item.getCountryCodes().contains("BO"))
                .map(ChecklistItem::getId).toList();
        assertThat(peruvian).isNotEmpty();
        assertThat(bolivian).isNotEmpty();

        // Peru's only destination goes, so the Peru links go with it.
        DestinationService.UnlinkResult result =
                destinationService.delete(TRIP_ID, SAM, cusco.getId());
        assertThat(result.checklistItemsUnlinked()).isEqualTo(peruvian.size());

        assertThat(peruvian).allSatisfy(id ->
                madeByThenChangedBy(reloadChecklist(id), ALEX, SAM));
        assertThat(bolivian).allSatisfy(id -> madeBy(reloadChecklist(id), ALEX));
    }

    @Test
    void aReorderStampsOnlyTheDestinationsThatActuallyMoved() {
        Destination first = destinationService
                .create(TRIP_ID, ALEX, place("Cusco", "PE", null, null)).destination();
        Destination second = destinationService
                .create(TRIP_ID, ALEX, place("Uyuni", "BO", null, null)).destination();
        Destination third = destinationService
                .create(TRIP_ID, ALEX, place("La Paz", "BO", null, null)).destination();

        // Swap the first two; the third keeps the position it already had.
        destinationService.reorder(TRIP_ID, SAM,
                List.of(second.getId(), first.getId(), third.getId()));

        madeByThenChangedBy(destinations.findById(SLUG, first.getId()).orElseThrow(), ALEX, SAM);
        madeByThenChangedBy(destinations.findById(SLUG, second.getId()).orElseThrow(), ALEX, SAM);
        madeBy(destinations.findById(SLUG, third.getId()).orElseThrow(), ALEX);
    }

    /**
     * The trip is the one record whose timestamps are stamped somewhere else:
     * YamlTripRepository has always set updatedAt on every save. So any service
     * method that saves a trip and does not stamp leaves the file reading
     * "changed at 13:17 by nobody" — which is how this was found, on real data,
     * after adding a member. Every trip-mutating path stamps now; this pins the
     * two that go through the member list.
     */
    @Test
    void addingAndRemovingAMemberRecordsWhoDidIt() {
        tripService.addMember(TRIP_ID, ALEX, "rainer@example.com");

        Trip afterAdd = trips.findById(TRIP_ID).orElseThrow();
        assertThat(afterAdd.getUpdatedByUserId()).isEqualTo(ALEX);
        assertThat(afterAdd.getUpdatedAt()).isNotNull();

        String rainer = afterAdd.getMembers().stream()
                .filter(m -> m.getEmail().equals("rainer@example.com"))
                .findFirst().orElseThrow().getUserId();
        tripService.removeMember(TRIP_ID, SAM, rainer);

        assertThat(trips.findById(TRIP_ID).orElseThrow().getUpdatedByUserId()).isEqualTo(SAM);
    }

    /**
     * The invariant behind the test above, stated directly: a trip that records
     * an edit must say who made it.
     */
    @Test
    void aTripNeverRecordsAnEditWithNoEditor() {
        tripService.update(TRIP_ID, SAM, "LATAM Trip 2026 (revised)",
                null, null, null);

        Trip trip = trips.findById(TRIP_ID).orElseThrow();
        assertThat(trip.getUpdatedAt()).isNotNull();
        assertThat(trip.getUpdatedByUserId()).isNotNull();
    }

    // ── storage ─────────────────────────────────────

    /**
     * The fields have to survive the YAML round trip to be worth anything, and
     * an untouched record should not be paying for two keys it cannot fill.
     */
    @Test
    void theStampReachesTheFileAndTheUpdatePairStaysOutUntilThereIsAnEdit() throws Exception {
        ChecklistItem item = checklistService.create(TRIP_ID, ALEX,
                new ChecklistService.Input(ChecklistCategory.OTHERS, "Buy a padlock", null, null));

        Path file = dir.resolve("travels/checklist/" + SLUG + ".yml");
        assertThat(Files.readString(file))
                .contains("createdByUserId: " + ALEX)
                .doesNotContain("updatedByUserId")
                .doesNotContain("updatedAt");

        checklistService.setStatus(TRIP_ID, SAM, item.getId(), ChecklistStatus.COMPLETED);

        assertThat(Files.readString(file))
                .contains("createdByUserId: " + ALEX)
                .contains("updatedByUserId: " + SAM);
        madeByThenChangedBy(reloadChecklist(item.getId()), ALEX, SAM);
    }

    /**
     * Deleting a trip must take its rendered public page with it.
     *
     * Not an audit concern, but it lives here because this is the only test
     * class wired with both TripService and a renderer. The failure mode it
     * guards is the worst kind: a published page is a plain static directory,
     * so once the trip data is gone nothing would ever remove it and it would
     * keep serving the whole plan at its public URL indefinitely. Three
     * orphaned directories from earlier sessions were found doing exactly
     * that.
     */
    @Test
    void deletingATripRemovesItsPublishedPage() throws Exception {
        Path published = dir.resolve("published").resolve(SLUG);
        Files.createDirectories(published);
        Files.writeString(published.resolve("index.html"), "<html>the whole plan</html>");
        assertThat(published).exists();

        tripService.delete(TRIP_ID, ALEX);

        assertThat(published).doesNotExist();
        assertThat(trips.findById(TRIP_ID)).isEmpty();
    }
}
