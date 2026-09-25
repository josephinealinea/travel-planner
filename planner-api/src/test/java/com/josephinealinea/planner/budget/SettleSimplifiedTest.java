package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.api.SettlementProperties;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settle Expenses with {@code app.settlement.simplify-debts} on: fewer
 * payments, worked out across the whole trip.
 *
 * <ul>
 *   <li><b>it is one plan for everybody.</b> Every member computes it
 *       independently, so it may depend on the trip's rows and never on who is
 *       looking, or two members would be told different things about the same
 *       money;</li>
 *   <li><b>the money is unchanged.</b> A member's payments add up to exactly
 *       their balance, and balances come from the same {@code shareOf} the
 *       Budget tab uses, so nothing is lost to rounding;</li>
 *   <li><b>the rules that were already true stay true</b> — charged rows only,
 *       per currency and never converted, a departed payer is not settled
 *       with, and a published page settles nothing.</li>
 * </ul>
 *
 * {@code SettleExpensesTest} remains the proof that the flag off is today's
 * behaviour.
 */
class SettleSimplifiedTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String RAY = "user-ray";
    private static final String DANA = "user-dana";
    private static final List<String> EVERYONE = List.of(ALEX, SAM, RAY, DANA);

    private Path tempDir;
    private BudgetRepository budget;
    private DestinationRepository destinations;
    private ItineraryRepository itinerary;
    private UserRepository users;
    private TripRepository trips;
    private TripAccessService access;
    private TripCountries tripCountries;
    private com.josephinealinea.planner.rates.api.RatesService rates;
    private BudgetService service;
    private Trip trip;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
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
        destinations = new YamlDestinationRepository(store, paths, locks);
        itinerary = new YamlItineraryRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);
        users = new YamlUserRepository(store, paths, locks);

        users.save(account(ALEX, "alex@example.com"));
        users.save(account(SAM, "sam@example.com"));
        users.save(account(RAY, "ray@example.com"));
        users.save(account(DANA, "dana@example.com"));

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

        service = serviceWith(true);
    }

    private BudgetService serviceWith(boolean simplify) {
        return new BudgetService(budget, itinerary, destinations, users, access, tripCountries,
                rates, new SettlementProperties(simplify));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static User account(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private void expense(String description, String amount, String currency,
                         List<String> sharers, String paidBy, boolean charged) {
        service.create(TRIP_ID, ALEX, new BudgetService.Input(
                description, ChecklistCategory.OTHERS, new BigDecimal(amount), currency,
                null, null, sharers, paidBy, charged));
    }

    private void charged(String description, String amount, List<String> sharers, String paidBy) {
        expense(description, amount, "EUR", sharers, paidBy, true);
    }

    private BudgetService.Summary summary(String userId) {
        return service.summarise(TRIP_ID, userId);
    }

    private List<BudgetService.Settlement> settlements(String userId) {
        return summary(userId).settlements();
    }

    private BudgetService.Settlement between(String me, String them, String currency) {
        return settlements(me).stream()
                .filter(s -> s.otherUserId().equals(them) && s.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no settlement between " + me + " and " + them + " in " + currency));
    }

    private BudgetService.Balance balance(String userId, String currency) {
        return summary(userId).balances().stream()
                .filter(b -> b.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no balance for " + userId + " in " + currency));
    }

    // ── the point of it ─────────────────────────────────────────────────

    /**
     * A owes nothing to anybody but C, in effect: Sam owes Alex and Ray owes
     * Sam, so Sam is a stop on the way that need not exist.
     */
    @Test
    void aChainCollapsesToOnePayment() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        charged("Tour", "100.00", List.of(RAY), SAM);

        assertThat(settlements(SAM)).isEmpty();

        BudgetService.Settlement ray = between(RAY, ALEX, "EUR");
        assertThat(ray.youOwe()).isEqualByComparingTo("100.00");
        assertThat(ray.owesYou()).isEqualByComparingTo("0.00");
        assertThat(ray.net()).isEqualByComparingTo("-100.00");
        assertThat(settlements(RAY)).hasSize(1);

        BudgetService.Settlement alex = between(ALEX, RAY, "EUR");
        assertThat(alex.net()).isEqualByComparingTo("100.00");
        assertThat(settlements(ALEX)).hasSize(1);
    }

    @Test
    void aCircleOfEqualDebtsCancelsOut() {
        charged("Hotel", "30.00", List.of(SAM), ALEX);
        charged("Tour", "30.00", List.of(RAY), SAM);
        charged("Lunch", "30.00", List.of(ALEX), RAY);

        for (String member : List.of(ALEX, SAM, RAY, DANA)) {
            assertThat(settlements(member)).as(member).isEmpty();
        }
    }

    /**
     * Square is still an answer: the details view shows the rows and a zero net
     * for someone who paid and owed in equal measure, and nothing at all for
     * someone no row involves.
     */
    @Test
    void aMemberWhoBalancesOutStillSeesWhy() {
        charged("Hotel", "30.00", List.of(SAM), ALEX);
        charged("Tour", "30.00", List.of(RAY), SAM);
        charged("Lunch", "30.00", List.of(ALEX), RAY);

        BudgetService.Balance sam = balance(SAM, "EUR");
        assertThat(sam.net()).isEqualByComparingTo("0.00");
        assertThat(sam.lines()).hasSize(2);
        assertThat(summary(DANA).balances()).isEmpty();
    }

    /** Both directions, mirrored: what one member owes is what the other is owed. */
    @Test
    void everyMemberIsToldTheSamePlan() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        charged("Tour", "100.00", List.of(RAY), SAM);

        assertThat(between(ALEX, RAY, "EUR").net())
                .isEqualByComparingTo(between(RAY, ALEX, "EUR").net().negate());
    }

    // ── money is unchanged ──────────────────────────────────────────────

    /**
     * Three-way tenner: 3.34 / 3.33 / 3.33. The payer is owed the other two
     * parts, and not a cent goes missing.
     */
    @Test
    void anOddCentIsNotLost() {
        charged("Dinner", "10.00", List.of(ALEX, SAM, RAY), ALEX);

        assertThat(between(SAM, ALEX, "EUR").youOwe()).isEqualByComparingTo("3.33");
        assertThat(between(RAY, ALEX, "EUR").youOwe()).isEqualByComparingTo("3.33");
        assertThat(between(ALEX, SAM, "EUR").owesYou()).isEqualByComparingTo("3.33");
        assertThat(balance(ALEX, "EUR").net()).isEqualByComparingTo("6.66");
    }

    /** A debt is repaid in the currency it was run up in, so nothing nets across two. */
    @Test
    void currenciesAreNeverNettedAgainstEachOther() {
        expense("Hotel", "100.00", "EUR", List.of(SAM), ALEX, true);
        expense("Tour", "100.00", "USD", List.of(ALEX), SAM, true);

        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo("100.00");
        assertThat(between(ALEX, SAM, "USD").net()).isEqualByComparingTo("-100.00");
        assertThat(settlements(ALEX)).hasSize(2);
    }

    @Test
    void aPendingRowSettlesNothing() {
        expense("Deposit", "200.00", "EUR", List.of(SAM), ALEX, false);

        assertThat(settlements(ALEX)).isEmpty();
        assertThat(summary(ALEX).balances()).isEmpty();
    }

    @Test
    void aPayerWhoHasLeftIsNotSettledWith() {
        charged("Dinner", "100.00", List.of(ALEX, SAM, RAY, DANA), SAM);
        trip.getMembers().removeIf(m -> m.getUserId().equals(SAM));
        trips.save(trip);

        assertThat(settlements(ALEX)).isEmpty();
        assertThat(settlements(RAY)).isEmpty();
    }

    // ── the balance breakdown ───────────────────────────────────────────

    /**
     * The modal's promise: the rows add up to the net, and the net is what the
     * member's payments add up to.
     */
    @Test
    void theBreakdownAddsUpToTheNetAndTheNetToThePayments() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);
        charged("Taxi", "60.00", List.of(ALEX, SAM), SAM);

        BudgetService.Balance alex = balance(ALEX, "EUR");
        assertThat(alex.net()).isEqualByComparingTo("20.00");
        assertThat(alex.lines()).hasSize(2);
        assertThat(alex.lines().stream().map(BudgetService.Balance.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(alex.net());
        assertThat(between(ALEX, SAM, "EUR").net()).isEqualByComparingTo(alex.net());

        BudgetService.Balance sam = balance(SAM, "EUR");
        assertThat(sam.net()).isEqualByComparingTo("-20.00");
        assertThat(sam.lines().stream().map(BudgetService.Balance.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(sam.net());
    }

    /** Paying for something only you share moves nobody's balance, so it is noise. */
    @Test
    void aRowThatNetsToNothingForYouIsLeftOut() {
        charged("Dinner", "100.00", List.of(SAM), ALEX);
        charged("My own lunch", "20.00", List.of(ALEX), ALEX);

        BudgetService.Balance alex = balance(ALEX, "EUR");
        assertThat(alex.lines()).hasSize(1);
        assertThat(alex.net()).isEqualByComparingTo("100.00");
    }

    @Test
    void aSimplifiedPaymentHasNoRowsBehindIt() {
        charged("Dinner", "100.00", List.of(SAM), ALEX);

        assertThat(between(ALEX, SAM, "EUR").lines()).isEmpty();
        assertThat(summary(ALEX).settlementsSimplified()).isTrue();
    }

    // ── the invariants, over a lot of rows ──────────────────────────────

    /**
     * Twelve hand-picked-by-a-seed rows between four people: however tangled,
     * the plan needs at most n−1 payments, each member's payments equal their
     * balance, and the plan is the same from every chair.
     */
    @Test
    void anyTangleNeedsAtMostOneFewerPaymentsThanPeople() {
        Random random = new Random(20260924L);
        for (int i = 0; i < 12; i++) {
            String payer = EVERYONE.get(random.nextInt(EVERYONE.size()));
            List<String> sharers = new ArrayList<>();
            for (String member : EVERYONE) {
                if (random.nextBoolean()) sharers.add(member);
            }
            String amount = (1 + random.nextInt(20000)) / 100.0 + "";
            charged("Row " + i, new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
                    sharers, payer);
        }

        int payments = 0;
        BigDecimal netOfEveryone = BigDecimal.ZERO;
        for (String member : EVERYONE) {
            BigDecimal sum = BigDecimal.ZERO;
            for (BudgetService.Settlement s : settlements(member)) {
                sum = sum.add(s.net());
                if (s.net().signum() < 0) payments++;
                // Mirrored from the other end.
                assertThat(s.net()).isEqualByComparingTo(
                        between(s.otherUserId(), member, s.currency()).net().negate());
            }
            BigDecimal balance = summary(member).balances().stream()
                    .map(BudgetService.Balance::net)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(sum).as(member + " payments vs balance").isEqualByComparingTo(balance);
            netOfEveryone = netOfEveryone.add(balance);
        }
        assertThat(payments).isLessThanOrEqualTo(EVERYONE.size() - 1);
        assertThat(netOfEveryone).isEqualByComparingTo("0.00");
    }

    // ── the flag off, and the published page ────────────────────────────

    @Test
    void withTheFlagOffNothingChanges() {
        service = serviceWith(false);
        charged("Hotel", "100.00", List.of(SAM), ALEX);
        charged("Tour", "100.00", List.of(RAY), SAM);

        // Pairwise, exactly as before: Sam is still a stop on the way.
        assertThat(settlements(SAM)).hasSize(2);
        assertThat(settlements(SAM).get(0).lines()).isNotEmpty();
        assertThat(summary(SAM).balances()).isEmpty();
        assertThat(summary(SAM).settlementsSimplified()).isFalse();
    }

    @Test
    void theSevenArgumentConstructorMeansOff() {
        service = new BudgetService(budget, itinerary, destinations, users, access,
                tripCountries, rates);
        charged("Hotel", "100.00", List.of(SAM), ALEX);

        assertThat(summary(ALEX).settlementsSimplified()).isFalse();
        assertThat(summary(ALEX).balances()).isEmpty();
    }

    @Test
    void theFlagDefaultsToOff() {
        assertThat(new SettlementProperties(null).simplifyDebts()).isFalse();
    }

    @Test
    void aPublishedPageCarriesNeitherSettlementsNorBalances() {
        charged("Hotel", "100.00", List.of(SAM), ALEX);

        BudgetService.Summary published = service.summarise(trips.findBySlug(SLUG).orElseThrow());
        assertThat(published.settlements()).isEmpty();
        assertThat(published.balances()).isEmpty();
    }
}
