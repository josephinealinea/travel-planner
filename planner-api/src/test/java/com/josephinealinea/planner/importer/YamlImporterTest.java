package com.josephinealinea.planner.importer;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.JdbcBudgetRepository;
import com.josephinealinea.planner.budget.infra.JdbcSettlementPaymentRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.JdbcChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.FeatureFlags;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.JdbcDestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.domain.PublishedPageSettings;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.JdbcUserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.JdbcItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.TestYamlPaths;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.JdbcTripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The one-off copy of a YAML data directory into PostgreSQL — the thing that
 * carries the user's real trips, and every friend's login, into the cloud.
 *
 * The importer verifies itself, but a check that only its own verifier runs
 * would be a check that only agrees with itself. So these tests reload what
 * was written independently, through the JDBC repositories, and state the
 * money a friend owes as numbers worked out by hand from the fixture — not as
 * "whatever the YAML side computes".
 *
 * <b>The fixtures are written straight to disk</b>, through YamlStore rather
 * than the YAML repositories, because the repositories stamp timestamps on
 * save. Written through them, every timestamp would read "now" — the same as
 * an import that wrongly stamped them — and the test that history survives
 * the import could not tell the two apart. So the fixture carries old,
 * microsecond-exact timestamps of its own.
 */
@PostgresTest
class YamlImporterTest {

    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String RAY = "user-ray";
    private static final String LATAM = "latam";
    private static final String TALLINN = "tallinn";

    @TempDir
    Path dir;

    private final YamlStore store = new YamlStore();
    private YamlPaths paths;

    private JdbcUserRepository users;
    private JdbcTripRepository trips;
    private JdbcDestinationRepository destinations;
    private JdbcChecklistRepository checklist;
    private JdbcItineraryRepository itinerary;
    private JdbcBudgetRepository budget;
    private JdbcSettlementPaymentRepository payments;
    private YamlImporter importer;

    @BeforeEach
    void setUp() throws IOException {
        paths = TestYamlPaths.under(dir);
        JdbcClient jdbc = PostgresTestDatabase.jdbc();
        PlatformTransactionManager tm = PostgresTestDatabase.transactionManager();
        users = new JdbcUserRepository(jdbc, tm);
        trips = new JdbcTripRepository(jdbc, tm);
        destinations = new JdbcDestinationRepository(jdbc, tm);
        checklist = new JdbcChecklistRepository(jdbc, tm);
        itinerary = new JdbcItineraryRepository(jdbc, tm);
        budget = new JdbcBudgetRepository(jdbc, tm);
        payments = new JdbcSettlementPaymentRepository(jdbc, tm);
        importer = new YamlImporter(users, trips, destinations, checklist, itinerary, budget, payments, jdbc, tm);
        writeFixtures();
    }

    // ── the import itself ───────────────────────────────────────────────

    @Test
    void importsEverythingAndItsOwnVerificationPasses() {
        ImportReport report = importer.run(dir, false);

        assertThat(report.outcome).as(explain(report)).isEqualTo(ImportReport.Outcome.COMMITTED);
        assertThat(report.verifier.passed()).isTrue();
        assertThat(report.counts)
                .containsEntry("users", 3)
                .containsEntry("trips", 2)
                .containsEntry("trip members", 5)
                .containsEntry("publish requests", 1)
                .containsEntry("destinations", 1)
                .containsEntry("checklist items", 3)
                .containsEntry("itinerary entries", 4)
                .containsEntry("budget rows", 5)
                .containsEntry("settlement payments", 1);
        assertThat(count("settlement_payments")).isEqualTo(1);
        assertThat(payments.findAll(TALLINN)).singleElement().satisfies(payment -> {
            assertThat(payment.getAmount()).isEqualByComparingTo("5.50");
            assertThat(payment.getNote()).isEqualTo("Cash at the hostel");
            assertThat(payment.getCreatedByUserId()).isEqualTo(ALEX);
            assertThat(payment.getFromUserId()).isEqualTo(ALEX);
            assertThat(payment.getToUserId()).isEqualTo(SAM);
        });
    }

    /**
     * The independent check: reload through the JDBC repositories and compare
     * with the YAML, record by record, in order — order being what decides
     * the odd cent of a split and a budget's tie-break on the same date.
     */
    @Test
    void everyRecordReloadsAsTheYamlHeldItInTheSameOrder() {
        importer.run(dir, false);

        TripLocks locks = new TripLocks();
        YamlUserRepository yamlUsers = new YamlUserRepository(store, paths, locks);
        YamlTripRepository yamlTrips = new YamlTripRepository(store, paths, locks);
        for (User user : yamlUsers.findAll()) {
            assertThat(users.findById(user.getId())).get().usingRecursiveComparison().isEqualTo(user);
        }
        for (String slug : List.of(LATAM, TALLINN)) {
            // exchangeRates: not stored in PostgreSQL by design (no longer read).
            assertThat(trips.findBySlug(slug)).get().usingRecursiveComparison()
                    .ignoringFields("exchangeRates").isEqualTo(yamlTrips.findBySlug(slug).orElseThrow());
            assertThat(destinations.findAll(slug)).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyElementsOf(new YamlDestinationRepository(store, paths, locks).findAll(slug));
            assertThat(checklist.findAll(slug)).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                    "legacyDestinationIds")
                    .containsExactlyElementsOf(new YamlChecklistRepository(store, paths, locks).findAll(slug));
            assertThat(itinerary.findAll(slug)).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                    "legacyDestinationIds")
                    .containsExactlyElementsOf(new YamlItineraryRepository(store, paths, locks).findAll(slug));
            assertThat(budget.findAll(slug)).usingRecursiveFieldByFieldElementComparatorIgnoringFields(
                    "legacyDestinationIds")
                    .containsExactlyElementsOf(new YamlBudgetRepository(store, paths, locks).findAll(slug));
        }
    }

    /** Everyone's history survives, rather than being rewritten as "the day of the import". */
    @Test
    void timestampsAndPasswordHashesSurviveUnchanged() {
        importer.run(dir, false);

        User alex = users.findById(ALEX).orElseThrow();
        assertThat(alex.getPasswordHash()).isEqualTo("$2a$10$alexhash");
        assertThat(alex.getCreatedAt()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(alex.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-10T08:30:00.123456Z"));
        // A user never edited since creation keeps having no updatedAt.
        assertThat(users.findById(SAM).orElseThrow().getUpdatedAt()).isNull();

        Trip latam = trips.findBySlug(LATAM).orElseThrow();
        assertThat(latam.getCreatedAt()).isEqualTo(Instant.parse("2026-09-05T10:00:00Z"));
        assertThat(latam.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-15T18:45:12.654321Z"));
    }

    /**
     * What Alex owes and is owed on the LATAM trip, worked out by hand from
     * the fixture below rather than taken from the YAML side:
     * <ul>
     *   <li>Dinner, 90.00 EUR, Alex paid, whole trip (3) — Sam and Ray owe 30.00 each;</li>
     *   <li>Taxi, 10.00 EUR, Sam paid, shared by Ray then Alex — Alex owes Sam 5.00;</li>
     *   <li>Museum, 33.34 USD, Ray paid, Alex/Sam/Ray — 11.12 + 11.11 + 11.11,
     *       the odd cent to the <i>first</i> sharer, so Alex owes Ray 11.12;</li>
     *   <li>Tour deposit, pending — owes nothing yet.</li>
     * </ul>
     */
    @Test
    void settlementsBetweenFriendsComeOutAsWorkedOutByHand() {
        importer.run(dir, false);

        BudgetService service = new BudgetService(budget, itinerary, destinations, users,
                new TripAccessService(trips), new TripCountries(destinations),
                ImportVerifier.fixedRates(new RateTable()));
        Map<String, BudgetService.Settlement> alex = service
                .summarise(trips.findBySlug(LATAM).orElseThrow(), users.findById(ALEX).orElseThrow())
                .settlements().stream()
                .collect(Collectors.toMap(s -> s.otherUserId() + "/" + s.currency(), s -> s));

        assertThat(alex).containsOnlyKeys(SAM + "/EUR", RAY + "/EUR", RAY + "/USD");
        assertMoney(alex.get(SAM + "/EUR"), "30.00", "5.00", "25.00");
        assertMoney(alex.get(RAY + "/EUR"), "30.00", "0.00", "30.00");
        assertMoney(alex.get(RAY + "/USD"), "0.00", "11.12", "-11.12");
    }

    // ── refusals and rollbacks ──────────────────────────────────────────

    @Test
    void aDatabaseThatAlreadyHoldsDataIsRefusedAndLeftAlone() {
        User live = new User();
        live.setId("user-live");
        live.setEmail("live@example.com");
        live.setPasswordHash("live-hash");
        users.save(live);

        ImportReport report = importer.run(dir, false);

        assertThat(report.outcome).isEqualTo(ImportReport.Outcome.REFUSED);
        assertThat(report.failure).contains("not empty");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("trips")).isZero();
    }

    @Test
    void aDryRunVerifiesEverythingAndWritesNothing() {
        ImportReport report = importer.run(dir, true);

        assertThat(report.outcome).as(explain(report)).isEqualTo(ImportReport.Outcome.DRY_RUN);
        assertThat(report.verifier.passed()).isTrue();
        assertThat(report.counts).containsEntry("budget rows", 5);
        for (String table : List.of("users", "trips", "trip_members", "publish_requests", "destinations",
                "checklist_items", "itinerary_items", "budget_items", "settlement_payments")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    /**
     * A row PostgreSQL cannot take — no id — in the second trip's budget, so
     * the failure comes after the users, both trips and the first trip's rows
     * are already written. All of it must go.
     */
    @Test
    void aFailurePartWayLeavesTheDatabaseExactlyAsEmptyAsItWas() throws IOException {
        Files.writeString(paths.budget(TALLINN), """
                - tripId: trip-tallinn
                  description: A row with no id
                  amount: 12
                  currency: EUR
                  paidByUserId: user-sam
                """);

        ImportReport report = importer.run(dir, false);

        assertThat(report.outcome).isEqualTo(ImportReport.Outcome.FAILED);
        assertThat(report.failure).isNotBlank();
        for (String table : List.of("users", "trips", "trip_members", "destinations", "budget_items",
                "settlement_payments")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    @Test
    void aDirectoryWithNoUsersFileIsRefused(@TempDir Path empty) {
        ImportReport report = importer.run(empty, false);

        assertThat(report.outcome).isEqualTo(ImportReport.Outcome.REFUSED);
        assertThat(report.failure).contains("users.yml");
    }

    @Test
    @SuppressWarnings("unchecked")
    void yamlModeIsRefusedBeforeAnythingIsRead() {
        AtomicInteger exitCode = new AtomicInteger(-1);
        ImportRunner runner = new ImportRunner(new ImportProperties(dir.toString(), false),
                new FeatureFlags(false),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                exitCode::set);

        runner.run(null);

        assertThat(exitCode.get()).isEqualTo(ImportRunner.REFUSED);
        assertThat(count("users")).isZero();
    }

    @Test
    void anOrdinaryStartImportsNothing() {
        AtomicInteger exitCode = new AtomicInteger(-1);
        @SuppressWarnings("unchecked")
        ImportRunner runner = new ImportRunner(new ImportProperties("", false), new FeatureFlags(true),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), mock(ObjectProvider.class),
                exitCode::set);

        runner.run(null);

        assertThat(exitCode.get()).as("an ordinary start must not exit").isEqualTo(-1);
    }

    // ── what the report tells the user to do next ───────────────────────

    @Test
    void thePublishedTripIsListedToBePublishedAgain() {
        ImportReport report = importer.run(dir, false);

        assertThat(report.published).extracting(ImportReport.PublishedTrip::slug).containsExactly(LATAM);
        assertThat(report.pendingRequests).singleElement().asString().contains(SAM);
    }

    /**
     * A row still carrying the pre-country {@code destinationIds} link imports
     * exactly as the app shows it today — YAML mode doesn't read that link
     * either — and the report says so rather than converting it silently.
     */
    @Test
    void aLegacyLinkImportsAsTheAppShowsItAndIsReported() {
        ImportReport report = importer.run(dir, false);

        assertThat(report.notes).anySatisfy(note -> assertThat(note).contains("pre-country destinationId"));
        BudgetItem legacy = budget.findById(TALLINN, "t-dinner").orElseThrow();
        assertThat(legacy.getCountryCodes()).isEmpty();
        assertThat(legacy.getSharedByUserIds()).isEmpty();
        // No status in the file: a charge, as everywhere else.
        assertThat(legacy.isConfirmed()).isTrue();
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private void writeFixtures() throws IOException {
        User alex = user(ALEX, "alex@example.com", "$2a$10$alexhash",
                "2026-09-01T10:00:00Z", "2026-09-10T08:30:00.123456Z");
        alex.setScreenName("alex");
        alex.setCurrencies(List.of("EUR", "USD"));
        alex.setDisplayCurrency("EUR");
        User sam = user(SAM, "sam@example.com", "$2a$10$samhash", "2026-09-02T09:00:00Z", null);
        sam.setDisplayCurrency("SGD");
        sam.setPublishedPage(new PublishedPageSettings(true, false, true));
        User ray = user(RAY, "ray@example.com", "$2a$10$rayhash", "2026-09-03T09:00:00Z", "2026-09-04T12:00:00Z");
        ray.setMustChangePassword(true);
        store.write(paths.users(), List.of(alex, sam, ray));

        Trip latam = trip("trip-latam", LATAM, "LATAM 2026", ALEX,
                "2026-09-05T10:00:00Z", "2026-09-15T18:45:12.654321Z");
        latam.getMembers().add(member(ALEX, TripRole.OWNER, null));
        latam.getMembers().add(member(SAM, TripRole.MEMBER, ALEX));
        latam.getMembers().add(member(RAY, TripRole.MEMBER, ALEX));
        latam.setStatus(TripStatus.PUBLISHED);
        latam.setPublishedAt(Instant.parse("2026-09-15T18:40:00Z"));
        PublishRequest request = new PublishRequest();
        request.setId("req-1");
        request.setRequestedByUserId(SAM);
        request.setStatus(PublishRequest.Status.PENDING);
        request.setRequestedAt(Instant.parse("2026-09-16T08:00:00Z"));
        request.setTheme("y2k");
        latam.getPublishRequests().add(request);

        Trip tallinn = trip("trip-tallinn", TALLINN, "Tallinn weekend", SAM, "2026-09-06T10:00:00Z", null);
        tallinn.getMembers().add(member(SAM, TripRole.OWNER, null));
        tallinn.getMembers().add(member(ALEX, TripRole.MEMBER, SAM));

        store.write(paths.trip(LATAM), latam);
        store.write(paths.trip(TALLINN), tallinn);
        store.write(paths.tripIndex(), List.of(
                new YamlTripRepository.IndexEntry("trip-latam", LATAM),
                new YamlTripRepository.IndexEntry("trip-tallinn", TALLINN)));

        Destination cusco = new Destination();
        cusco.setId("d-cusco");
        cusco.setTripId("trip-latam");
        cusco.setName("Cusco");
        cusco.setCountryCode("PE");
        cusco.setCountryFlag("🇵🇪");
        cusco.setLatitude(-13.53);
        cusco.setLongitude(-71.97);
        cusco.setGeonameId(3941584L);
        cusco.setTimezone("America/Lima");
        cusco.setStartDate(LocalDate.parse("2026-10-25"));
        cusco.setEndDate(LocalDate.parse("2026-10-31"));
        cusco.setLodgingSeeded(true);
        cusco.setCreatedAt(Instant.parse("2026-09-05T11:00:00Z"));
        cusco.setCreatedByUserId(ALEX);
        store.write(paths.destinations(LATAM), List.of(cusco));

        ChecklistItem transport = checklistItem("c-transport", "Plan transportation to Cusco",
                ChecklistCategory.TRANSPORTATION, 0);
        ChecklistItem lodging = checklistItem("c-lodging", "Plan 6N accommodation in Cusco",
                ChecklistCategory.LODGING, 1);
        ChecklistItem activities = checklistItem("c-activities", "Plan activities in Cusco",
                ChecklistCategory.ACTIVITIES, 2);
        activities.setStatus(ChecklistStatus.COMPLETED);
        activities.setCompletedAt(Instant.parse("2026-09-12T09:15:00Z"));
        store.write(paths.checklist(LATAM), List.of(transport, lodging, activities));

        // A stay spread over its nights: the plan's own row, then two days
        // pointing back at it through planId.
        ItineraryItem flight = itineraryItem("i-flight", ChecklistCategory.TRANSPORTATION, "Flight to Lima",
                "2026-10-24T06:00", "2026-10-24T11:00");
        ItineraryItem stay = itineraryItem("i-stay", ChecklistCategory.LODGING, "Airbnb in Cusco",
                "2026-10-27T15:00", "2026-10-29T11:00");
        stay.setChecklistItemId("c-lodging");
        stay.setCost(new BigDecimal("200.00"));
        stay.setCurrency("EUR");
        ItineraryItem night2 = itineraryItem("i-stay-2", ChecklistCategory.LODGING, "Airbnb in Cusco",
                "2026-10-28T00:00", null);
        night2.setPlanId("i-stay");
        night2.setAllDay(true);
        ItineraryItem night3 = itineraryItem("i-stay-3", ChecklistCategory.LODGING, "Airbnb in Cusco",
                "2026-10-29T00:00", null);
        night3.setPlanId("i-stay");
        night3.setAllDay(true);
        store.write(paths.itinerary(LATAM), List.of(flight, stay, night2, night3));

        // Dinner and Taxi share a date, so their order is the file's (F3).
        store.write(paths.budget(LATAM), List.of(
                expense("b-dinner", "Dinner", "90.00", "EUR", "2026-10-25", ALEX, List.of(), true),
                expense("b-taxi", "Taxi", "10.00", "EUR", "2026-10-25", SAM, List.of(RAY, ALEX), true),
                expense("b-museum", "Museum", "33.34", "USD", "2026-10-26", RAY, List.of(ALEX, SAM, RAY), true),
                expense("b-deposit", "Tour deposit", "200", "EUR", "2026-10-27", ALEX, List.of(SAM), false)));

        // Hand-written, like a file from before countries: a legacy link, no
        // status, no sharers.
        Files.writeString(paths.budget(TALLINN), """
                - id: t-dinner
                  tripId: trip-tallinn
                  category: FOOD
                  description: Old dinner
                  amount: 40
                  currency: EUR
                  destinationIds:
                  - dest-old
                  paidByUserId: user-sam
                """);

        // A settlement payment, hand-written like the row above: the amount's
        // scale and the audit fields have to survive, and the verifier's
        // settlement comparison reads it on both sides.
        Files.createDirectories(paths.settlements(TALLINN).getParent());
        Files.writeString(paths.settlements(TALLINN), """
                - id: pay-1
                  tripId: trip-tallinn
                  fromUserId: user-alex
                  toUserId: user-sam
                  amount: 5.50
                  currency: EUR
                  date: 2026-10-01
                  note: Cash at the hostel
                  createdAt: 2026-10-01T18:00:00Z
                  createdByUserId: user-alex
                """);
    }

    private static User user(String id, String email, String hash, String createdAt, String updatedAt) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setPasswordHash(hash);
        user.setCreatedAt(Instant.parse(createdAt));
        user.setUpdatedAt(updatedAt == null ? null : Instant.parse(updatedAt));
        return user;
    }

    private static Trip trip(String id, String slug, String title, String owner, String createdAt, String updatedAt) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setSlug(slug);
        trip.setTitle(title);
        trip.setOwnerUserId(owner);
        trip.setStartDate(LocalDate.parse("2026-10-24"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.setCreatedAt(Instant.parse(createdAt));
        trip.setUpdatedAt(updatedAt == null ? null : Instant.parse(updatedAt));
        trip.setCreatedByUserId(owner);
        return trip;
    }

    private static TripMember member(String userId, TripRole role, String invitedBy) {
        TripMember member = new TripMember(userId, role, invitedBy);
        member.setInvitedAt(Instant.parse("2026-09-05T12:00:00Z"));
        return member;
    }

    private static ChecklistItem checklistItem(String id, String description, ChecklistCategory category, int sort) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setTripId("trip-latam");
        item.setDescription(description);
        item.setCategory(category);
        item.setAutoSeeded(true);
        item.setSeededFromDestinationId("d-cusco");
        item.setCountryCodes(List.of("PE"));
        item.setSortOrder(sort);
        item.setCreatedAt(Instant.parse("2026-09-05T11:00:00Z"));
        item.setCreatedByUserId(ALEX);
        return item;
    }

    private static ItineraryItem itineraryItem(String id, ChecklistCategory category, String description,
                                               String startAt, String endAt) {
        ItineraryItem item = new ItineraryItem();
        item.setId(id);
        item.setTripId("trip-latam");
        item.setCategory(category);
        item.setDescription(description);
        item.setStartAt(LocalDateTime.parse(startAt));
        item.setEndAt(endAt == null ? null : LocalDateTime.parse(endAt));
        item.setCountryCodes(List.of("PE"));
        item.setCreatedAt(Instant.parse("2026-09-06T10:00:00Z"));
        item.setCreatedByUserId(ALEX);
        return item;
    }

    private static BudgetItem expense(String id, String description, String amount, String currency, String date,
                                      String paidBy, List<String> sharers, boolean charged) {
        BudgetItem item = new BudgetItem();
        item.setId(id);
        item.setTripId("trip-latam");
        item.setCategory(ChecklistCategory.OTHERS);
        item.setDescription(description);
        item.setAmount(new BigDecimal(amount));
        item.setCurrency(currency);
        item.setDate(LocalDate.parse(date));
        item.setPaidByUserId(paidBy);
        item.setSharedByUserIds(sharers);
        item.setCountryCodes(List.of("PE"));
        item.markCharged(charged, Instant.parse("2026-09-16T10:00:00Z"));
        item.setCreatedAt(Instant.parse("2026-09-16T10:00:00Z"));
        item.setCreatedByUserId(paidBy);
        return item;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void assertMoney(BudgetService.Settlement settlement, String owesYou, String youOwe, String net) {
        assertThat(settlement.owesYou()).as("owes you").isEqualByComparingTo(owesYou);
        assertThat(settlement.youOwe()).as("you owe").isEqualByComparingTo(youOwe);
        assertThat(settlement.net()).as("net").isEqualByComparingTo(net);
    }

    private static long count(String table) {
        return PostgresTestDatabase.jdbc().sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** The report as the user would see it — what a failed assertion should show. */
    private static String explain(ImportReport report) {
        var out = new java.io.ByteArrayOutputStream();
        report.print(new java.io.PrintStream(out, true));
        return out.toString();
    }
}
