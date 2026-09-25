package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.api.SettlementProperties;
import com.josephinealinea.planner.budget.api.SettlementService;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlSettlementPaymentRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recording a payment so a debt can reach zero.
 *
 * <ul>
 *   <li><b>a payment changes no figure.</b> It is money between two people who
 *       already spent it, so Total, both breakdowns, native totals and the
 *       forecast must read exactly the same with or without it. This is the
 *       whole reason it is a record of its own rather than an expense;</li>
 *   <li><b>to the ledger it is "paid by from, shared by to"</b> — the payer
 *       credited, the receiver debited, per currency, never converted — in
 *       both the pairwise view and the simplified plan;</li>
 *   <li><b>either party or the owner</b> may record or delete one; the author
 *       is stored; a payment naming somebody who has left is ignored, like a
 *       departed payer's expense;</li>
 *   <li><b>it never reaches a published page.</b></li>
 * </ul>
 */
class SettlementPaymentTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";   // owner
    private static final String SAM = "user-sam";
    private static final String RAY = "user-ray";
    private static final String DANA = "user-dana";
    private static final String OUTSIDER = "user-outsider";

    private Path dir;
    private BudgetRepository budget;
    private SettlementPaymentRepository payments;
    private DestinationRepository destinations;
    private ItineraryRepository itinerary;
    private UserRepository users;
    private TripRepository trips;
    private TripAccessService access;
    private TripCountries tripCountries;
    private com.josephinealinea.planner.rates.api.RatesService rates;
    private BudgetService budgetService;
    private SettlementService settlement;
    private Trip trip;

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

        budget = new YamlBudgetRepository(store, paths, locks);
        payments = new YamlSettlementPaymentRepository(store, paths, locks);
        destinations = new YamlDestinationRepository(store, paths, locks);
        itinerary = new YamlItineraryRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);
        users = new YamlUserRepository(store, paths, locks);

        for (String id : List.of(ALEX, SAM, RAY, DANA, OUTSIDER)) {
            users.save(account(id));
        }

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(ALEX);
        trip.setDisplayCurrency("EUR");
        trip.setStartDate(LocalDate.parse("2026-10-20"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.getMembers().add(new TripMember(ALEX, TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, TripRole.MEMBER, ALEX));
        trip.getMembers().add(new TripMember(RAY, TripRole.MEMBER, ALEX));
        trip.getMembers().add(new TripMember(DANA, TripRole.MEMBER, ALEX));
        trips.save(trip);

        access = new TripAccessService(trips);
        tripCountries = new TripCountries(destinations);
        rates = TestRates.with(store, paths, props, Map.of("USD", new BigDecimal("1.10")));

        useSimplify(false);
    }

    private void useSimplify(boolean simplify) {
        budgetService = new BudgetService(budget, itinerary, destinations, users, access,
                tripCountries, rates, new SettlementProperties(simplify), payments);
        settlement = new SettlementService(payments, access);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static User account(String id) {
        User user = new User();
        user.setId(id);
        user.setEmail(id.replace("user-", "") + "@example.com");
        return user;
    }

    private void charged(String description, String amount, String currency,
                         List<String> sharers, String paidBy) {
        budgetService.create(TRIP_ID, ALEX, new BudgetService.Input(
                description, ChecklistCategory.OTHERS, new BigDecimal(amount), currency,
                null, null, sharers, paidBy, true));
    }

    private void charged(String description, String amount, List<String> sharers, String paidBy) {
        charged(description, amount, "EUR", sharers, paidBy);
    }

    private SettlementPayment pay(String actor, String from, String to, String amount) {
        return settlement.record(TRIP_ID, actor, new SettlementService.Input(
                from, to, new BigDecimal(amount), "EUR", null, null));
    }

    private BudgetService.Summary summary(String userId) {
        return budgetService.summarise(TRIP_ID, userId);
    }

    private BudgetService.Settlement between(String me, String them, String currency) {
        return summary(me).settlements().stream()
                .filter(s -> s.otherUserId().equals(them) && s.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no settlement between " + me + " and " + them + " in " + currency));
    }

    private static void assertStatus(Runnable action, HttpStatus status) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(status));
    }

    // ── pairwise: a debt reaches zero ───────────────────────────────────

    @Test
    void aFullPaymentBringsADebtToZero() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);

        pay(SAM, SAM, ALEX, "100.00");

        BudgetService.Settlement alex = between(ALEX, SAM, "EUR");
        assertThat(alex.owesYou()).isEqualByComparingTo("100.00");
        assertThat(alex.paidYou()).isEqualByComparingTo("100.00");
        assertThat(alex.net()).isEqualByComparingTo("0.00");

        BudgetService.Settlement sam = between(SAM, ALEX, "EUR");
        assertThat(sam.youOwe()).isEqualByComparingTo("100.00");
        assertThat(sam.youPaid()).isEqualByComparingTo("100.00");
        assertThat(sam.net()).isEqualByComparingTo("0.00");
    }

    @Test
    void aPartialPaymentLeavesTheRemainder() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);

        pay(SAM, SAM, ALEX, "40.00");

        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("60.00");
        assertThat(between(SAM, ALEX, "EUR").net()).isEqualByComparingTo("-60.00");
    }

    @Test
    void payingMoreThanIsOwedFlipsTheDirection() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);

        pay(SAM, SAM, ALEX, "130.00");

        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("-30.00");
    }

    @Test
    void aPaymentAgainstNoExpenseStillShows() {
        pay(SAM, SAM, ALEX, "25.00");

        BudgetService.Settlement alex = between(ALEX, SAM, "EUR");
        assertThat(alex.paidYou()).isEqualByComparingTo("25.00");
        assertThat(alex.net()).isEqualByComparingTo("-25.00");
    }

    @Test
    void aPaymentInOneCurrencyLeavesAnotherAlone() {
        charged("Hotel", "100.00", "EUR", List.of(SAM), ALEX);
        charged("Tour", "100.00", "USD", List.of(SAM), ALEX);

        pay(SAM, SAM, ALEX, "100.00");

        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("0.00");
        assertThat(between(ALEX, SAM, "USD").net()).isEqualByComparingTo("100.00");
    }

    // ── simplified: the plan shrinks ────────────────────────────────────

    @Test
    void aPaymentRemovesItselfFromTheSimplifiedPlan() {
        useSimplify(true);
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        charged("Tour", "100.00", List.of(RAY), SAM);
        assertThat(between(RAY, ALEX, "EUR").net()).isEqualByComparingTo("-100.00");

        pay(RAY, RAY, ALEX, "100.00");

        for (String member : List.of(ALEX, SAM, RAY, DANA)) {
            assertThat(summary(member).settlements()).as(member).isEmpty();
        }
    }

    @Test
    void aPartialPaymentShrinksTheSimplifiedPlan() {
        useSimplify(true);
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        charged("Tour", "100.00", List.of(RAY), SAM);

        pay(RAY, RAY, ALEX, "40.00");

        assertThat(between(RAY, ALEX, "EUR").net()).isEqualByComparingTo("-60.00");
        assertThat(between(ALEX, RAY, "EUR").net()).isEqualByComparingTo("60.00");
    }

    @Test
    void thePaymentIsOneOfTheRowsBehindTheBalance() {
        useSimplify(true);
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        SettlementPayment payment = pay(SAM, SAM, ALEX, "30.00");

        BudgetService.Balance alex = summary(ALEX).balances().get(0);
        assertThat(alex.net()).isEqualByComparingTo("70.00");
        assertThat(alex.lines()).extracting(BudgetService.Balance.Line::paymentId)
                .contains(payment.getId());
        assertThat(alex.lines().stream().map(BudgetService.Balance.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(alex.net());
    }

    @Test
    void aSquareMemberStillSeesThePaymentBehindTheirZero() {
        useSimplify(true);
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        pay(SAM, SAM, ALEX, "100.00");

        BudgetService.Balance alex = summary(ALEX).balances().get(0);
        assertThat(alex.net()).isEqualByComparingTo("0.00");
        assertThat(alex.lines()).hasSize(2);
    }

    // ── the reason it is its own record ─────────────────────────────────

    @Test
    void aPaymentChangesNoFigureAnywhere() {
        charged("Hotel", "100.00", List.of(SAM, ALEX), ALEX);
        charged("Tour", "60.00", "USD", List.of(SAM, ALEX, RAY), RAY);
        expensePending("Deposit", "50.00", List.of(SAM), ALEX);

        Map<String, BudgetService.Summary> before = new java.util.LinkedHashMap<>();
        for (String member : List.of(ALEX, SAM, RAY, DANA)) before.put(member, summary(member));

        pay(SAM, SAM, ALEX, "50.00");
        pay(RAY, RAY, SAM, "12.34");

        for (String member : List.of(ALEX, SAM, RAY, DANA)) {
            BudgetService.Summary after = summary(member);
            BudgetService.Summary was = before.get(member);
            assertThat(after.charged()).as(member + " charged").isEqualTo(was.charged());
            assertThat(after.forecast()).as(member + " forecast").isEqualTo(was.forecast());
            assertThat(after.shares()).as(member + " shares").isEqualTo(was.shares());
            assertThat(after.items()).as(member + " rows").hasSameSizeAs(was.items());
        }
    }

    private void expensePending(String description, String amount, List<String> sharers, String paidBy) {
        budgetService.create(TRIP_ID, ALEX, new BudgetService.Input(
                description, ChecklistCategory.OTHERS, new BigDecimal(amount), "EUR",
                null, null, sharers, paidBy, false));
    }

    // ── who may record and delete ───────────────────────────────────────

    @Test
    void thePayerReceiverAndOwnerMayRecord() {
        assertThat(pay(SAM, SAM, RAY, "5.00")).isNotNull();   // the payer
        assertThat(pay(RAY, SAM, RAY, "5.00")).isNotNull();   // the receiver
        assertThat(pay(ALEX, SAM, RAY, "5.00")).isNotNull();  // the owner, for two others
        assertThat(payments.findAll(SLUG)).hasSize(3);
    }

    @Test
    void aThirdMemberMayNot() {
        assertStatus(() -> pay(DANA, SAM, RAY, "5.00"), HttpStatus.FORBIDDEN);
        assertThat(payments.findAll(SLUG)).isEmpty();
    }

    @Test
    void aNonMemberGetsNotFound() {
        assertStatus(() -> pay(OUTSIDER, SAM, RAY, "5.00"), HttpStatus.NOT_FOUND);
    }

    @Test
    void theSameRulesApplyToDeleting() {
        SettlementPayment payment = pay(SAM, SAM, RAY, "5.00");

        assertStatus(() -> settlement.delete(TRIP_ID, DANA, payment.getId()), HttpStatus.FORBIDDEN);
        assertStatus(() -> settlement.delete(TRIP_ID, OUTSIDER, payment.getId()), HttpStatus.NOT_FOUND);
        assertThat(payments.findAll(SLUG)).hasSize(1);

        settlement.delete(TRIP_ID, RAY, payment.getId());   // the receiver
        assertThat(payments.findAll(SLUG)).isEmpty();

        SettlementPayment again = pay(SAM, SAM, RAY, "5.00");
        settlement.delete(TRIP_ID, ALEX, again.getId());    // the owner
        assertThat(payments.findAll(SLUG)).isEmpty();
    }

    @Test
    void deletingAPaymentBringsTheDebtBack() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        SettlementPayment payment = pay(SAM, SAM, ALEX, "100.00");
        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("0.00");

        settlement.delete(TRIP_ID, SAM, payment.getId());

        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("100.00");
    }

    @Test
    void deletingSomethingThatIsNotThereIsNotFound() {
        assertStatus(() -> settlement.delete(TRIP_ID, ALEX, "nope"), HttpStatus.NOT_FOUND);
    }

    // ── refusals ────────────────────────────────────────────────────────

    @Test
    void nobodyPaysThemselves() {
        assertStatus(() -> pay(SAM, SAM, SAM, "5.00"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void bothEndsMustBeOnTheTrip() {
        assertStatus(() -> pay(ALEX, SAM, OUTSIDER, "5.00"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> pay(ALEX, OUTSIDER, SAM, "5.00"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void theAmountMustBePositive() {
        assertStatus(() -> pay(SAM, SAM, RAY, "0.00"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> pay(SAM, SAM, RAY, "-3.00"), HttpStatus.BAD_REQUEST);
        assertStatus(() -> settlement.record(TRIP_ID, SAM,
                new SettlementService.Input(SAM, RAY, null, "EUR", null, null)), HttpStatus.BAD_REQUEST);
    }

    // ── what is stored ──────────────────────────────────────────────────

    @Test
    void theAuthorIsStoredAndNobodyHasEditedIt() {
        SettlementPayment payment = pay(ALEX, SAM, RAY, "5.00");

        SettlementPayment stored = payments.findById(SLUG, payment.getId()).orElseThrow();
        assertThat(stored.getCreatedByUserId()).isEqualTo(ALEX);
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedByUserId()).isNull();
        assertThat(stored.getFromUserId()).isEqualTo(SAM);
        assertThat(stored.getToUserId()).isEqualTo(RAY);
    }

    @Test
    void currencyAndDateDefaultToTheTripsAndToday() {
        SettlementPayment payment = settlement.record(TRIP_ID, SAM,
                new SettlementService.Input(SAM, RAY, new BigDecimal("5.00"), null, null, null));

        assertThat(payment.getCurrency()).isEqualTo("EUR");
        assertThat(payment.getDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void theNoteIsTrimmedAndBlankIsNone() {
        SettlementPayment noted = settlement.record(TRIP_ID, SAM, new SettlementService.Input(
                SAM, RAY, new BigDecimal("5.00"), "EUR", null, "  cash  "));
        SettlementPayment blank = settlement.record(TRIP_ID, SAM, new SettlementService.Input(
                SAM, RAY, new BigDecimal("5.00"), "EUR", null, "   "));

        assertThat(noted.getNote()).isEqualTo("cash");
        assertThat(blank.getNote()).isNull();
    }

    // ── who is left ─────────────────────────────────────────────────────

    /** The trip no longer knows them, so it does not settle with them — as with a departed payer. */
    @Test
    void aPaymentFromSomeoneWhoHasLeftIsIgnored() {
        charged("Dinner", "90.00", List.of(ALEX, SAM, RAY), ALEX);
        pay(SAM, SAM, ALEX, "30.00");
        trip.getMembers().removeIf(m -> m.getUserId().equals(SAM));
        trips.save(trip);

        // Sam's share drops out of the split (Alex and Ray owe 45 each), and Sam's
        // 30 must not still be counted against Alex.
        assertThat(between(ALEX, RAY, "EUR").net()).isEqualByComparingTo("45.00");
        assertThat(summary(ALEX).settlements()).hasSize(1);
        useSimplify(true);
        assertThat(summary(ALEX).balances().get(0).net()).isEqualByComparingTo("45.00");
    }

    // ── the published page, and the trip ────────────────────────────────

    @Test
    void aPublishedPageCarriesNoPayments() {
        pay(SAM, SAM, ALEX, "25.00");

        BudgetService.Summary published = budgetService.summarise(trips.findBySlug(SLUG).orElseThrow());
        assertThat(published.payments()).isEmpty();
        assertThat(published.settlements()).isEmpty();
    }

    @Test
    void aMemberSeesTheTripsPayments() {
        SettlementPayment payment = pay(SAM, SAM, RAY, "5.00");

        assertThat(summary(DANA).payments()).extracting(SettlementPayment::getId)
                .containsExactly(payment.getId());
    }

    @Test
    void deletingTheTripDeletesItsPayments() {
        pay(SAM, SAM, RAY, "5.00");

        trips.delete(trip);

        assertThat(payments.findAll(SLUG)).isEmpty();
        assertThat(dir.resolve("travels").resolve("settlements").resolve(SLUG + ".yml")).doesNotExist();
    }
}
