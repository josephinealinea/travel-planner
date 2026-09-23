package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
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
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who owes whom, which is the question a trip ends on.
 *
 * Everything needed to answer it was already stored — "Shared by" says whose
 * money a row is, "Paid by" says who actually handed it over — and this is the
 * one place that reads the two together. The properties worth pinning:
 *
 * <ul>
 *   <li><b>the figures must reconcile with the Budget tab to the cent.</b>
 *       They come from {@link com.josephinealinea.planner.trips.api.TripMembers}
 *       shareOf, the one place that owns division, so a three-way tenner
 *       settles as 3.34/3.33/3.33 and the detail lines add up to the total
 *       shown above them;</li>
 *   <li><b>a debt needs money to have moved.</b> A pending row is an
 *       intention, so it settles nothing;</li>
 *   <li><b>nobody owes themselves.</b> The payer's own share of a row they
 *       paid for is not a debt, and counting it would have every member owing
 *       for their own dinner;</li>
 *   <li><b>a currency is never converted.</b> A debt is repaid in the currency
 *       it was incurred in, so two currencies with one member are two lines;</li>
 *   <li><b>a published page settles nothing.</b> There is no signed-in reader
 *       to settle for, and a member's name against a figure is exactly what a
 *       public file must never carry.</li>
 * </ul>
 */
class SettleExpensesTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String RAY = "user-ray";

    private BudgetService service;
    private TripRepository trips;
    private Trip trip;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
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

        BudgetRepository budget = new YamlBudgetRepository(store, paths, locks);
        DestinationRepository destinations = new YamlDestinationRepository(store, paths, locks);
        ChecklistRepository checklist = new YamlChecklistRepository(store, paths, locks);
        ItineraryRepository itinerary = new YamlItineraryRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);
        UserRepository users = new YamlUserRepository(store, paths, locks);

        users.save(account(ALEX, "alex@example.com"));
        users.save(account(SAM, "sam@example.com"));
        users.save(account(RAY, "ray@example.com"));

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
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        TripCountries tripCountries = new TripCountries(destinations);
        var rates = TestRates.with(store, paths, props, Map.of("USD", new BigDecimal("1.10")));

        service = new BudgetService(budget, itinerary, destinations, users, access, tripCountries, rates);
        // Constructed for parity with the other budget tests; no plan is needed
        // here, because a plan's cost reaches settle-up as an ordinary row.
        new ItineraryService(itinerary, destinations,
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

    private void expense(String description, String amount, String currency,
                         List<String> sharers, String paidBy, boolean charged) {
        service.create(TRIP_ID, ALEX, new BudgetService.Input(
                description, ChecklistCategory.OTHERS, new BigDecimal(amount), currency,
                null, null, sharers, paidBy, charged));
    }

    private void charged(String description, String amount, List<String> sharers, String paidBy) {
        expense(description, amount, "EUR", sharers, paidBy, true);
    }

    private List<BudgetService.Settlement> settlements(String userId) {
        return service.summarise(TRIP_ID, userId).settlements();
    }

    private Optional<BudgetService.Settlement> between(String me, String them, String currency) {
        return settlements(me).stream()
                .filter(s -> s.otherUserId().equals(them) && s.currency().equals(currency))
                .findFirst();
    }

    private BudgetService.Settlement require(String me, String them, String currency) {
        return between(me, them, currency).orElseThrow(() ->
                new AssertionError("no settlement between " + me + " and " + them + " in " + currency));
    }

    // ── the two directions ──────────────────────────────────────────────

    /**
     * The headline case: one debt each way, and the net is what anybody
     * actually hands over. Read from both ends, because a settlement that did
     * not mirror would have two members disagreeing about the same money.
     */
    @Test
    void whatTheyOweYouAndWhatYouOweThemNetOut() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);
        charged("Taxi", "60.00", List.of(ALEX, SAM), SAM);

        BudgetService.Settlement mine = require(ALEX, SAM, "EUR");
        assertThat(mine.owesYou()).isEqualByComparingTo("50.00");
        assertThat(mine.youOwe()).isEqualByComparingTo("30.00");
        assertThat(mine.net()).isEqualByComparingTo("20.00");

        BudgetService.Settlement theirs = require(SAM, ALEX, "EUR");
        assertThat(theirs.owesYou()).isEqualByComparingTo("30.00");
        assertThat(theirs.youOwe()).isEqualByComparingTo("50.00");
        assertThat(theirs.net()).isEqualByComparingTo("-20.00");
    }

    /** A plan nobody has paid for settles nothing — there is no money to return. */
    @Test
    void aPendingRowIsNotADebt() {
        expense("Tour deposit", "200.00", "EUR", List.of(ALEX, SAM), ALEX, false);

        assertThat(settlements(ALEX)).isEmpty();
    }

    /**
     * The mistake that would make every member owe for their own dinner: the
     * payer's half of what they paid for is theirs already.
     */
    @Test
    void thePayersOwnShareIsNotChargedToThem() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);

        assertThat(require(ALEX, SAM, "EUR").owesYou()).isEqualByComparingTo("50.00");
        assertThat(require(ALEX, SAM, "EUR").youOwe()).isEqualByComparingTo("0.00");
    }

    /** Naming nobody means the whole trip, here as everywhere else. */
    @Test
    void aRowSharedByNobodySplitsAcrossTheWholeTrip() {
        charged("Group tour", "90.00", List.of(), ALEX);

        assertThat(require(ALEX, SAM, "EUR").owesYou()).isEqualByComparingTo("30.00");
        assertThat(require(ALEX, RAY, "EUR").owesYou()).isEqualByComparingTo("30.00");
    }

    /** A debt is repaid in the currency it was run up in, so these never merge. */
    @Test
    void twoCurrenciesAreTwoLines() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);
        expense("Hotel", "220.00", "USD", List.of(ALEX, SAM), ALEX, true);

        assertThat(require(ALEX, SAM, "EUR").owesYou()).isEqualByComparingTo("50.00");
        assertThat(require(ALEX, SAM, "USD").owesYou()).isEqualByComparingTo("110.00");
    }

    /**
     * The cent rule, which is the whole reason this is computed here rather
     * than divided again in the browser: the leftover penny goes to the
     * earliest sharer, and the lines behind a total have to add up to it.
     */
    @Test
    void threeWaySplitsToTheCentAndTheLinesAddUp() {
        charged("Tenner", "10.00", List.of(ALEX, SAM, RAY), ALEX);

        BudgetService.Settlement sam = require(ALEX, SAM, "EUR");
        BudgetService.Settlement ray = require(ALEX, RAY, "EUR");
        assertThat(sam.owesYou()).isEqualByComparingTo("3.33");
        assertThat(ray.owesYou()).isEqualByComparingTo("3.33");

        // Alex keeps 3.34 of it, and the whole tenner is accounted for.
        assertThat(sam.owesYou().add(ray.owesYou())).isEqualByComparingTo("6.66");

        BigDecimal fromLines = sam.lines().stream()
                .map(BudgetService.Settlement.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(fromLines).isEqualByComparingTo(sam.owesYou());
    }

    /** Somebody else's dinner, between two other people, is not your business. */
    @Test
    void aRowYouNeitherPaidNorShareIsNotYours() {
        charged("Their dinner", "40.00", List.of(SAM, RAY), SAM);

        assertThat(settlements(ALEX)).isEmpty();
    }

    /**
     * A member who has left cannot be settled with: the trip no longer knows
     * them, the same reason their share drops out of a split.
     */
    @Test
    void aPayerWhoHasLeftTheTripIsNotSettledWith() {
        charged("Ray's round", "60.00", List.of(ALEX, RAY), RAY);
        assertThat(between(ALEX, RAY, "EUR")).isPresent();

        trip.getMembers().removeIf(member -> RAY.equals(member.getUserId()));
        trips.save(trip);

        assertThat(between(ALEX, RAY, "EUR")).isEmpty();
    }

    /**
     * A published page has no signed-in reader to settle for, and naming a
     * member beside a figure is exactly what a public file must never do.
     */
    @Test
    void aPublishedPageHasNoSettlements() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);

        assertThat(service.summarise(trip).settlements()).isEmpty();
    }

    /** One line per contributing row, pointing back at it by id. */
    @Test
    void theDetailLinesNameTheRowsBehindTheTotal() {
        charged("Dinner", "100.00", List.of(ALEX, SAM), ALEX);
        charged("Museum", "20.00", List.of(ALEX, SAM), ALEX);
        charged("Taxi", "60.00", List.of(ALEX, SAM), SAM);

        BudgetService.Settlement sam = require(ALEX, SAM, "EUR");

        assertThat(sam.lines()).hasSize(3);
        assertThat(sam.lines()).filteredOn(BudgetService.Settlement.Line::owedToYou).hasSize(2);
        assertThat(sam.lines()).allSatisfy(line ->
                assertThat(line.itemId()).isNotBlank());
    }
}
