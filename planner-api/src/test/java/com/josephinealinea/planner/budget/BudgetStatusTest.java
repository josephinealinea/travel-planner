package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.domain.BudgetStatus;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
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
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.rates.TestRates;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Charged versus pending, and what each one is allowed to count towards.
 *
 * The feature is one boolean on a form, and every interesting property of it is
 * about what that boolean must <i>not</i> do:
 *
 * <ul>
 *   <li>it must not reclassify the rows that existed before it. A file with no
 *       {@code status} in it is a file full of charges, and a budget that woke
 *       up one morning calling the whole trip speculative would be worse than
 *       one that never knew the difference;</li>
 *   <li>the headline total must not quietly absorb money nobody has paid. That
 *       is what the separate forecast rollup is for, and the two have to stay
 *       apart while sharing one set of rows;</li>
 *   <li>a pending row must not vanish. It is excluded from the total and listed
 *       in the table — the opposite of hidden;</li>
 *   <li>a confirmation date must not move. Re-editing a charge made in March
 *       has to leave March alone, and un-ticking has to clear it rather than
 *       leave a pending row claiming to have been paid.</li>
 * </ul>
 *
 * The defaults pull in opposite directions on purpose and are pinned here as
 * such: an expense typed in by hand starts charged, a plan's cost starts
 * pending.
 */
class BudgetStatusTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String USER_ID = "user-1";

    private BudgetService service;
    private ItineraryService itineraryService;
    private BudgetRepository budget;
    private DestinationRepository destinations;
    private Trip trip;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.dir = tempDir;
        AppProperties props = new AppProperties(
                new AppProperties.Storage(tempDir.toString()),
                new AppProperties.Publish(tempDir.resolve("published").toString(), null),
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

        budget = new BudgetRepository(store, paths, locks);
        destinations = new DestinationRepository(store, paths, locks);
        ChecklistRepository checklist = new ChecklistRepository(store, paths, locks);
        ItineraryRepository itinerary = new ItineraryRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);
        UserRepository users = new YamlUserRepository(store, paths, locks);

        User user = new User();
        user.setId(USER_ID);
        user.setEmail("you@example.com");
        users.save(user);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.setDisplayCurrency("EUR");
        trip.setStartDate(LocalDate.parse("2026-10-20"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.getMembers().add(new TripMember(USER_ID, "you@example.com", TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        TripCountries tripCountries = new TripCountries(destinations);
        var rates = TestRates.with(store, paths, props, Map.of("USD", new BigDecimal("1.10")));

        service = new BudgetService(budget, itinerary, destinations, users, access, tripCountries, rates);
        itineraryService = new ItineraryService(itinerary, destinations,
                new ChecklistService(checklist, itinerary, access, tripCountries), access,
                new PlanTemplates(), new BudgetSync(budget),
                new CountryCatalog(RestClient.create()), tripCountries);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * A payer on every fixture, pending ones included: a charged row has to
     * name one, and the tests that start pending and then tick the box would
     * otherwise be refused at exactly the transition they exist to check.
     * Who paid moves no figure here.
     */
    private BudgetService.Input expense(String description, String amount, Boolean charged) {
        return new BudgetService.Input(description, ChecklistCategory.OTHERS,
                new BigDecimal(amount), "EUR", null, null, null, USER_ID, charged);
    }

    private BudgetItem reload(String id) {
        return budget.findById(SLUG, id).orElseThrow();
    }

    private Destination place(String id, String countryCode, String countryName) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId(TRIP_ID);
        destination.setName(id);
        destination.setCountryCode(countryCode);
        destination.setCountryName(countryName);
        return destinations.save(SLUG, destination);
    }

    // ── the defaults, which point opposite ways ─────────────────────────

    @Test
    void anExpenseAddedByHandIsChargedAndStampsWhenItWasConfirmed() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Vaccine", "45.00", null));

        assertThat(reload(created.getId()).getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(reload(created.getId()).getConfirmedAt()).isNotNull();
    }

    @Test
    void untickingTheBoxOnAnExpenseRecordsItAsPendingWithNoConfirmationDate() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Tour deposit", "80.00", false));

        assertThat(reload(created.getId()).getStatus()).isEqualTo(BudgetStatus.PENDING);
        assertThat(reload(created.getId()).getConfirmedAt()).isNull();
    }

    @Test
    void aPlansCostCreatesAPendingBudgetRow() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, USER_ID, plan("Hotel in Cusco", "240.00", null));

        BudgetItem row = budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(BudgetStatus.PENDING);
        assertThat(row.getConfirmedAt()).isNull();
    }

    @Test
    void aPlanWhoseBoxWasTickedCreatesAChargedBudgetRow() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, USER_ID, plan("Hotel in Cusco", "240.00", true));

        BudgetItem row = budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(row.getConfirmedAt()).isNotNull();
    }

    private ItineraryService.Input plan(String description, String cost, Boolean charged) {
        return new ItineraryService.Input(null, ChecklistCategory.LODGING, description,
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal(cost), "EUR", charged, null, USER_ID, List.of());
    }

    // ── the transitions ─────────────────────────────────────────────────

    @Test
    void tickingTheBoxOnAPendingExpenseConfirmsItAndStampsTheTime() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Tour deposit", "80.00", false));

        service.update(TRIP_ID, USER_ID, created.getId(),
                new BudgetService.Input(null, null, null, null, null, null, null, null, true));

        assertThat(reload(created.getId()).getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(reload(created.getId()).getConfirmedAt()).isNotNull();
    }

    @Test
    void untickingAConfirmedExpenseClearsItsConfirmationDate() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Vaccine", "45.00", true));
        assertThat(reload(created.getId()).getConfirmedAt()).isNotNull();

        service.update(TRIP_ID, USER_ID, created.getId(),
                new BudgetService.Input(null, null, null, null, null, null, null, null, false));

        assertThat(reload(created.getId()).getStatus()).isEqualTo(BudgetStatus.PENDING);
        assertThat(reload(created.getId()).getConfirmedAt()).isNull();
    }

    /**
     * The one that would go quietly wrong. Correcting the description of a
     * charge made in March must not restamp it as today, or the confirmation
     * date would only ever record the last edit.
     */
    @Test
    void correctingAChargedExpenseLeavesTheOriginalConfirmationDateAlone() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Vaccine", "45.00", true));
        Instant confirmed = reload(created.getId()).getConfirmedAt();

        service.update(TRIP_ID, USER_ID, created.getId(),
                new BudgetService.Input("Yellow fever vaccine", null, new BigDecimal("50.00"),
                        null, null, null, null, null, true));

        assertThat(reload(created.getId()).getConfirmedAt()).isEqualTo(confirmed);
        assertThat(reload(created.getId()).getDescription()).isEqualTo("Yellow fever vaccine");
    }

    /** A patch that says nothing about the box leaves the status where it was. */
    @Test
    void repricingAnExpenseWithoutSendingTheBoxDoesNotChangeItsStatus() {
        BudgetItem created = service.create(TRIP_ID, USER_ID, expense("Tour deposit", "80.00", false));

        service.update(TRIP_ID, USER_ID, created.getId(),
                new BudgetService.Input(null, null, new BigDecimal("95.00"), null, null, null, null, null, null));

        assertThat(reload(created.getId()).getStatus()).isEqualTo(BudgetStatus.PENDING);
    }

    /**
     * A plan edit may move its row's status, because the Plan form shows the
     * row's real status and submitting it is the member saying what it should
     * be. Everything else about the row stays the member's own — the narrowness
     * BudgetSync is built around.
     */
    @Test
    void tickingTheBoxOnAnExistingPlanConfirmsTheRowItCreated() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, USER_ID, plan("Hotel in Cusco", "240.00", null));
        String rowId = budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow().getId();

        itineraryService.update(TRIP_ID, USER_ID, plan.getId(),
                new ItineraryService.Input(null, null, null, null, null, null,
                        new BigDecimal("240.00"), "EUR", true, null, null, null));

        assertThat(reload(rowId).getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
    }

    // ── the rows that came before this existed ──────────────────────────

    /**
     * The migration rule, asserted against a file rather than an object: a
     * record whose YAML has no status at all reads as a charge, so nothing
     * written before this field existed is reclassified by its arrival.
     */
    @Test
    void aRecordWithNoStatusInItsYamlReadsAsCharged() throws IOException {
        Path file = dir.resolve("travels/budget/" + SLUG + ".yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                - id: legacy-1
                  tripId: trip-1
                  category: LODGING
                  description: Hotel booked last year
                  amount: 300
                  currency: EUR
                """);

        BudgetItem loaded = reload("legacy-1");

        assertThat(loaded.getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(loaded.isConfirmed()).isTrue();
    }

    // ── what each rollup counts ─────────────────────────────────────────

    @Test
    void theChargedTotalLeavesOutPendingExpensesAndTheForecastTotalCountsThem() {
        service.create(TRIP_ID, USER_ID, expense("Vaccine", "45.00", true));
        service.create(TRIP_ID, USER_ID, expense("Tour deposit", "80.00", false));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.charged().total()).isEqualByComparingTo("45.00");
        assertThat(summary.forecast().total()).isEqualByComparingTo("125.00");
    }

    /** Excluded from the total, still listed in the table. */
    @Test
    void aPendingExpenseIsStillOneOfTheRowsTheTableLists() {
        service.create(TRIP_ID, USER_ID, expense("Tour deposit", "80.00", false));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.items()).hasSize(1);
        assertThat(summary.charged().total()).isEqualByComparingTo("0.00");
    }

    @Test
    void theCategorySlicesSplitTheSameWayTheTotalsDo() {
        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Flight", ChecklistCategory.TRANSPORTATION,
                new BigDecimal("400.00"), "EUR", null, null, null, USER_ID, true));
        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Hotel", ChecklistCategory.LODGING,
                new BigDecimal("300.00"), "EUR", null, null, null, null, false));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.charged().byCategory().get("TRANSPORTATION")).isEqualByComparingTo("400.00");
        assertThat(summary.charged().byCategory().get("LODGING")).isEqualByComparingTo("0");
        assertThat(summary.forecast().byCategory().get("LODGING")).isEqualByComparingTo("300.00");
    }

    @Test
    void theCountrySlicesSplitTheSameWayTheTotalsDo() {
        place("Cusco", "PE", "Peru");
        place("La Paz", "BO", "Bolivia");

        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Hotel in Cusco",
                ChecklistCategory.LODGING, new BigDecimal("200.00"), "EUR", null, List.of("PE"), null, USER_ID, true));
        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Salt flats tour",
                ChecklistCategory.ACTIVITIES, new BigDecimal("150.00"), "EUR", null, List.of("BO"), null, null, false));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.charged().byCountry()).hasSize(1);
        assertThat(summary.charged().byCountry().get(0).key()).isEqualTo("PE");
        assertThat(summary.forecast().byCountry()).hasSize(2);
    }

    /**
     * Native totals belong to their own rollup too. "What did this actually
     * cost in USD" has a different answer depending on whether the money has
     * left yet, and the figure sits directly under the total it must agree
     * with.
     */
    @Test
    void nativeTotalsFollowTheRollupTheyAreShownUnder() {
        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Vaccine", ChecklistCategory.OTHERS,
                new BigDecimal("110.00"), "USD", null, null, null, USER_ID, true));
        service.create(TRIP_ID, USER_ID, new BudgetService.Input("Tour deposit", ChecklistCategory.ACTIVITIES,
                new BigDecimal("220.00"), "USD", null, null, null, null, false));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.charged().nativeTotals()).hasSize(1);
        assertThat(summary.charged().nativeTotals().get(0).amount()).isEqualByComparingTo("110.00");
        assertThat(summary.forecast().nativeTotals().get(0).amount()).isEqualByComparingTo("330.00");
    }
}
