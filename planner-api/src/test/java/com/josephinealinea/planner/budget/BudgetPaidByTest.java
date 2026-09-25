package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.rates.TestRates;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who put the money down for an expense — the "Paid by" beside "Shared by".
 *
 * The two answer different questions, and the rules pinned here are about
 * keeping them apart:
 *
 * <ul>
 *   <li><b>a payer is one member or nobody.</b> An id that is not a member is
 *       a stale form, not something to drop quietly;</li>
 *   <li><b>on a patch, absent and empty mean different things.</b> Absent is a
 *       form that said nothing and must leave the payer alone; an empty string
 *       is a member clearing it. That is the contract the frontend codes to;</li>
 *   <li><b>a charged expense must name a payer.</b> Money that has demonstrably
 *       left someone's hand with no record of whose can appear in no
 *       settlement, so it is refused at every door into that state rather than
 *       stored and quietly skipped. A pending row may still have none — nobody
 *       has paid it, so naming a payer would be a guess;</li>
 *   <li><b>the payer changes no figure.</b> Whose money a row is stays decided
 *       by "Shared by" alone — paying for the table's dinner does not make it
 *       your dinner.</li>
 * </ul>
 */
class BudgetPaidByTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";

    private Path dir;
    private BudgetService service;
    private ItineraryService itineraryService;
    private BudgetRepository budget;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        dir = tempDir;
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

        budget = new YamlBudgetRepository(store, paths, locks);
        DestinationRepository destinations = new YamlDestinationRepository(store, paths, locks);
        ChecklistRepository checklist = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itinerary = new YamlItineraryRepository(store, paths, locks);
        TripRepository trips = new YamlTripRepository(store, paths, locks);
        UserRepository users = new YamlUserRepository(store, paths, locks);

        users.save(account(ALEX, "alex@example.com"));
        users.save(account(SAM, "sam@example.com"));

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setDisplayCurrency("EUR");
        trip.setStartDate(LocalDate.parse("2026-10-20"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.getMembers().add(new TripMember(ALEX, TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, TripRole.MEMBER, ALEX));
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

    private static User account(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private BudgetItem expense(String amount, List<String> sharers, String paidBy) {
        return charged(amount, sharers, paidBy, true);
    }

    /** A row nobody has paid yet, which is the one place a payer stays optional. */
    private BudgetItem pendingExpense(String amount, List<String> sharers, String paidBy) {
        return charged(amount, sharers, paidBy, false);
    }

    private BudgetItem charged(String amount, List<String> sharers, String paidBy, boolean charged) {
        return service.create(TRIP_ID, ALEX, new BudgetService.Input(
                "Dinner in Cusco", ChecklistCategory.FOOD, new BigDecimal(amount), "EUR",
                null, null, sharers, paidBy, charged));
    }

    /** A patch that sends nothing but the payer, the way only a payer changes. */
    private BudgetItem patchPayer(String itemId, String paidBy) {
        return service.update(TRIP_ID, ALEX, itemId, new BudgetService.Input(
                null, null, null, null, null, null, null, paidBy, null));
    }

    private BudgetItem reload(String itemId) {
        return budget.findById(SLUG, itemId).orElseThrow();
    }

    private ItineraryItem planWithCost(String paidBy) {
        return planWithCost(paidBy, true);
    }

    private ItineraryItem planWithCost(String paidBy, boolean costCharged) {
        return itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", costCharged, null, paidBy, List.of()));
    }

    // ── an expense typed into the budget ────────────────────────────────

    @Test
    void anExpenseRecordsWhoPaidForIt() {
        BudgetItem row = expense("80.00", List.of(), SAM);

        assertThat(reload(row.getId()).getPaidByUserId()).isEqualTo(SAM);
    }

    /**
     * Both of the ways a form can say "nobody" are refused on a charged row.
     * This is the first of three doors into charged-with-no-payer; the other
     * two are ticking the box and clearing the payer afterwards.
     */
    @Test
    void aChargedExpenseMustNameWhoPaid() {
        assertThatThrownBy(() -> expense("80.00", List.of(), null))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");

        assertThatThrownBy(() -> expense("80.00", List.of(), ""))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");
    }

    /** Nobody has paid a pending row, so there is nobody to name. */
    @Test
    void aPendingExpenseNeedsNoPayer() {
        BudgetItem absent = pendingExpense("80.00", List.of(), null);
        BudgetItem empty = pendingExpense("80.00", List.of(), "");

        assertThat(reload(absent.getId()).getPaidByUserId()).isNull();
        assertThat(reload(empty.getId()).getPaidByUserId()).isNull();
    }

    /** The second door: a pending row with no payer being marked charged. */
    @Test
    void tickingChargedOnAPayerlessRowIsRefused() {
        BudgetItem row = pendingExpense("80.00", List.of(), null);

        assertThatThrownBy(() -> service.update(TRIP_ID, ALEX, row.getId(),
                new BudgetService.Input(null, null, null, null, null, null, null, null, true)))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");

        assertThat(reload(row.getId()).isConfirmed()).isFalse();
    }

    /** Ticking it in the same breath as naming a payer is the way through. */
    @Test
    void tickingChargedWhileNamingAPayerIsAllowed() {
        BudgetItem row = pendingExpense("80.00", List.of(), null);

        service.update(TRIP_ID, ALEX, row.getId(), new BudgetService.Input(
                null, null, null, null, null, null, null, SAM, true));

        BudgetItem saved = reload(row.getId());
        assertThat(saved.isConfirmed()).isTrue();
        assertThat(saved.getPaidByUserId()).isEqualTo(SAM);
    }

    /** Absent means "the form said nothing", which must leave the row alone. */
    @Test
    void patchingWithoutAPayerLeavesItAlone() {
        BudgetItem row = expense("80.00", List.of(), SAM);

        patchPayer(row.getId(), null);

        assertThat(reload(row.getId()).getPaidByUserId()).isEqualTo(SAM);
    }

    /** An empty string is the one way a member clears a payer they set. */
    @Test
    void patchingWithAnEmptyPayerClearsIt() {
        BudgetItem row = pendingExpense("80.00", List.of(), SAM);

        patchPayer(row.getId(), "");

        assertThat(reload(row.getId()).getPaidByUserId()).isNull();
    }

    /**
     * The third door. Clearing is still how a member says "I got this wrong",
     * but it cannot be the last word on a row that says the money is spent.
     */
    @Test
    void clearingThePayerOfAChargedRowIsRefused() {
        BudgetItem row = expense("80.00", List.of(), SAM);

        assertThatThrownBy(() -> patchPayer(row.getId(), ""))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");

        assertThat(reload(row.getId()).getPaidByUserId()).isEqualTo(SAM);
    }

    /** Clearing and un-ticking together is a member saying it was never paid. */
    @Test
    void clearingThePayerWhileUnTickingChargedIsAllowed() {
        BudgetItem row = expense("80.00", List.of(), SAM);

        service.update(TRIP_ID, ALEX, row.getId(), new BudgetService.Input(
                null, null, null, null, null, null, null, "", false));

        BudgetItem saved = reload(row.getId());
        assertThat(saved.getPaidByUserId()).isNull();
        assertThat(saved.isConfirmed()).isFalse();
    }

    @Test
    void aPayerWhoIsNotAMemberIsRejected() {
        assertThatThrownBy(() -> expense("80.00", List.of(), "user-nobody"))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.trip.notABuddy");

        BudgetItem row = expense("80.00", List.of(), SAM);
        assertThatThrownBy(() -> patchPayer(row.getId(), "user-nobody"))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.trip.notABuddy");
        assertThat(reload(row.getId()).getPaidByUserId()).isEqualTo(SAM);
    }

    // ── a plan's cost ───────────────────────────────────────────────────

    @Test
    void aPlansCostCarriesItsPayerOntoTheBudgetRowItCreates() {
        ItineraryItem plan = planWithCost(SAM);

        assertThat(budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow()
                .getPaidByUserId()).isEqualTo(SAM);
    }

    @Test
    void repricingAPlanWithoutSendingAPayerLeavesItAsItWas() {
        ItineraryItem plan = planWithCost(SAM);

        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null,
                new BigDecimal("300.00"), "EUR", null, null, null, null));

        BudgetItem row = budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow();
        assertThat(row.getAmount()).isEqualByComparingTo("300.00");
        assertThat(row.getPaidByUserId()).isEqualTo(SAM);
    }

    /** The itinerary endpoints take the same absent / empty / id contract. */
    @Test
    void aPlanEditCanChangeThePayerOfItsBudgetRow() {
        ItineraryItem plan = planWithCost(SAM);

        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, ALEX, null));
        assertThat(budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow()
                .getPaidByUserId()).isEqualTo(ALEX);

        assertThatThrownBy(() -> planWithCost("user-nobody"))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.trip.notABuddy");
    }

    /** Clearing stays available on a plan whose cost nobody has paid yet. */
    @Test
    void aPlanEditCanClearThePayerOfAPendingBudgetRow() {
        ItineraryItem plan = planWithCost(SAM, false);

        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null, null, null, null, null, "", null));

        assertThat(budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow()
                .getPaidByUserId()).isNull();
    }

    /**
     * The fourth door, and the reason the rule lives in BudgetSync too: a plan
     * is the other way into a budget row, and a rule enforced only on the
     * budget's own form would leave it wide open.
     */
    @Test
    void aPlansChargedCostMustNameWhoPaid() {
        assertThatThrownBy(() -> planWithCost(null, true))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");

        ItineraryItem plan = planWithCost(SAM, false);
        assertThatThrownBy(() -> itineraryService.update(TRIP_ID, ALEX, plan.getId(),
                new ItineraryService.Input(
                        null, null, null, null, null, null, null, null, true, null, "", null)))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.expense.payerRequired");
    }

    // ── the payer is not a share ────────────────────────────────────────

    /**
     * Paying for something shared is exactly when this field gets used, and
     * exactly when counting it would go wrong: the payer's budget would take
     * the whole dinner rather than their half of it.
     */
    @Test
    void thePayerChangesNoFigure() {
        BudgetItem paidByAlex = expense("100.00", List.of(ALEX, SAM), ALEX);
        BudgetService.Summary before = service.summarise(TRIP_ID, ALEX);

        patchPayer(paidByAlex.getId(), SAM);
        BudgetService.Summary after = service.summarise(TRIP_ID, ALEX);

        assertThat(before.charged().total()).isEqualByComparingTo("50.00");
        assertThat(after.charged().total()).isEqualByComparingTo(before.charged().total());
        assertThat(after.forecast().total()).isEqualByComparingTo(before.forecast().total());
        assertThat(after.shares()).isEqualTo(before.shares());
    }

    // ── on disk ─────────────────────────────────────────────────────────

    @Test
    void thePayerSurvivesAYamlRoundTrip() throws IOException {
        BudgetItem row = expense("80.00", List.of(), SAM);

        Path file = dir.resolve("travels/budget/" + SLUG + ".yml");
        assertThat(Files.readString(file)).contains("paidByUserId: " + SAM);
        assertThat(reload(row.getId()).getPaidByUserId()).isEqualTo(SAM);

        // Every row written before this field existed has no payer, and must
        // read as nobody rather than fail.
        Files.writeString(file, """
                - id: legacy-1
                  tripId: trip-1
                  category: FOOD
                  description: Dinner last year
                  amount: 30
                  currency: EUR
                """);
        assertThat(reload("legacy-1").getPaidByUserId()).isNull();
    }

    /**
     * The rule above is enforced on write, never on read: these files are
     * hand-editable and older installs exist, and a file that refused to load
     * would be far worse than one row reading oddly.
     *
     * So a charged row that predates the field still loads, and the settlement
     * it belongs in reads it as paid by whoever recorded it — the same
     * reasoning as everywhere else here, that a record the app wrote for you
     * belongs to the member whose action produced it. Asserted against a
     * hand-written file rather than an object, the way BudgetStatusTest does,
     * because the whole point is what happens to a file nothing here wrote.
     */
    @Test
    void aChargedRowWithNoStoredPayerReadsAsItsCreator() throws IOException {
        expense("80.00", List.of(), SAM);

        Path file = dir.resolve("travels/budget/" + SLUG + ".yml");
        Files.writeString(file, """
                - id: legacy-charged
                  tripId: trip-1
                  category: FOOD
                  description: Dinner last year
                  amount: 30
                  currency: EUR
                  status: CONFIRMED
                  createdByUserId: user-sam
                """);

        assertThat(reload("legacy-charged").getPaidByUserId()).isNull();
        assertThat(BudgetService.effectivePayerOf(reload("legacy-charged"))).isEqualTo(SAM);
    }
}
