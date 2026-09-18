package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
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

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whose money an expense is, and how much of it counts as theirs.
 *
 * The Budget tab shows a member their own costs, which makes this the one place
 * in the app where a row can be present in the file and absent from a screen.
 * Everything worth pinning down here is about that not going wrong:
 *
 * <ul>
 *   <li><b>no row may be invisible to everybody.</b> An expense naming nobody
 *       belongs to the whole trip, and so does one naming only people who have
 *       since left it — the alternative is money that exists on disk and on
 *       no member's budget;</li>
 *   <li><b>the shares have to add back up.</b> They are what each member's
 *       totals are built from, so three people splitting a tenner must come to
 *       exactly ten, not 9.99;</li>
 *   <li><b>filtering the totals must not filter the rows.</b> The other tabs
 *       read every budget row — the Plan form wants the status of the row its
 *       own cost created, whoever shares it — so `items` stays whole and
 *       `shares` is what narrows the view;</li>
 *   <li><b>a shared amount must be divided before it is converted and sliced,
 *       not after.</b> A half-share of a USD flight has to reach the country
 *       breakdown as a half-share.</li>
 * </ul>
 */
class BudgetSharingTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String ALEX = "user-alex";
    private static final String SAM = "user-sam";
    private static final String RAY = "user-ray";

    private BudgetService service;
    private ItineraryService itineraryService;
    private BudgetRepository budget;
    private ItineraryRepository itinerary;
    private DestinationRepository destinations;
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

        budget = new BudgetRepository(store, paths, locks);
        destinations = new DestinationRepository(store, paths, locks);
        ChecklistRepository checklist = new ChecklistRepository(store, paths, locks);
        itinerary = new ItineraryRepository(store, paths, locks);
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
        trip.getMembers().add(new TripMember(ALEX, "alex@example.com", TripRole.OWNER, null));
        trip.getMembers().add(new TripMember(SAM, "sam@example.com", TripRole.MEMBER, ALEX));
        trip.getMembers().add(new TripMember(RAY, "ray@example.com", TripRole.MEMBER, ALEX));
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

    /**
     * Alex pays for everything here. Who paid is irrelevant to sharing — it
     * moves no figure, which thePayerChangesNoFigure pins — but a charged row
     * has to name somebody, so these fixtures name the member creating them.
     */
    private BudgetItem expense(String description, String amount, List<String> sharers) {
        return service.create(TRIP_ID, ALEX, new BudgetService.Input(
                description, ChecklistCategory.OTHERS, new BigDecimal(amount), "EUR",
                null, null, sharers, ALEX, true));
    }

    /** Summarises as one member, the way the Budget tab asks. */
    private BudgetService.Summary as(String userId) {
        return service.summarise(TRIP_ID, userId);
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

    // ── dividing ────────────────────────────────────────────────────────

    @Test
    void anExpenseSharedByTwoMembersCountsHalfOnEachOfTheirBudgets() {
        expense("Taxi", "100.00", List.of(ALEX, SAM));

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("50.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("50.00");
    }

    @Test
    void anExpenseNamingOneMemberCountsWholeOnTheirsAndNotAtAllOnAnybodyElses() {
        expense("Souvenir", "40.00", List.of(SAM));

        assertThat(as(SAM).charged().total()).isEqualByComparingTo("40.00");
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("0.00");
        assertThat(as(RAY).charged().total()).isEqualByComparingTo("0.00");
    }

    /**
     * The shares are what every total is built from, so they have to be exact
     * rather than merely close: a tenner split three ways must still be a
     * tenner once the three budgets are added back together.
     */
    @Test
    void sharesAreExactToTheCentAndAddBackUpToTheWholeAmount() {
        expense("Three-way lunch", "10.00", List.of(ALEX, SAM, RAY));

        BigDecimal alex = as(ALEX).charged().total();
        BigDecimal sam = as(SAM).charged().total();
        BigDecimal ray = as(RAY).charged().total();

        assertThat(alex).isEqualByComparingTo("3.34");
        assertThat(sam).isEqualByComparingTo("3.33");
        assertThat(ray).isEqualByComparingTo("3.33");
        assertThat(alex.add(sam).add(ray)).isEqualByComparingTo("10.00");
    }

    // ── no row may be invisible ─────────────────────────────────────────

    @Test
    void anExpenseNamingNobodyBelongsToTheWholeTripAndDividesBetweenEveryMember() {
        expense("Shared van hire", "300.00", List.of());

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("100.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("100.00");
        assertThat(as(RAY).charged().total()).isEqualByComparingTo("100.00");
    }

    /**
     * The nastier version of the same rule. A row naming only people who have
     * since left the trip would otherwise be money on nobody's budget, which is
     * exactly the state a member leaving should not be able to create.
     */
    @Test
    void anExpenseWhoseOnlySharerHasLeftTheTripFallsBackToTheWholeTrip() {
        BudgetItem row = expense("Ray's tour", "60.00", List.of(RAY));
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("0.00");

        trip.getMembers().removeIf(member -> RAY.equals(member.getUserId()));
        trips.save(trip);

        // Two members left, so 30.00 each rather than 60.00 charged to nobody.
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("30.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("30.00");
        assertThat(budget.findById(SLUG, row.getId()).orElseThrow().getSharedByUserIds())
                .containsExactly(RAY);
    }

    /** A member who has left stops taking a share of what remains shared. */
    @Test
    void aDepartedMemberDropsOutOfASplitWithoutChangingTheStoredNames() {
        expense("Taxi", "90.00", List.of(ALEX, SAM, RAY));
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("30.00");

        trip.getMembers().removeIf(member -> RAY.equals(member.getUserId()));
        trips.save(trip);

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("45.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("45.00");
    }

    // ── filtering the totals does not filter the rows ───────────────────

    @Test
    void everyRowIsStillListedWhoeverIsAskingAndSharesSaysWhichAreTheirs() {
        BudgetItem mine = expense("Souvenir", "40.00", List.of(SAM));
        BudgetItem theirs = expense("Alex's hat", "20.00", List.of(ALEX));

        BudgetService.Summary summary = as(SAM);

        assertThat(summary.items()).hasSize(2);
        assertThat(summary.shares()).containsOnlyKeys(mine.getId());
        assertThat(summary.shares().get(mine.getId())).isEqualByComparingTo("40.00");
        assertThat(summary.shares()).doesNotContainKey(theirs.getId());
    }

    /**
     * A published page has no signed-in reader to divide for, so it reports
     * what the trip cost rather than what anybody's part of it was.
     */
    @Test
    void withNoSignedInMemberEveryRowCountsInFull() {
        expense("Taxi", "100.00", List.of(ALEX, SAM));
        expense("Souvenir", "40.00", List.of(SAM));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.charged().total()).isEqualByComparingTo("140.00");
        assertThat(summary.shares()).isEmpty();
    }

    // ── dividing happens before converting and slicing ──────────────────

    @Test
    void aSharedAmountIsDividedBeforeItIsConvertedAndBrokenDown() {
        place("Cusco", "PE", "Peru");
        service.create(TRIP_ID, ALEX, new BudgetService.Input(
                "Hotel in Cusco", ChecklistCategory.LODGING, new BigDecimal("220.00"), "USD",
                null, List.of("PE"), List.of(ALEX, SAM), ALEX, true));

        BudgetService.Summary summary = as(ALEX);

        // 110.00 USD at 1.10 per EUR — the share converted, not the whole 220.
        assertThat(summary.charged().total()).isEqualByComparingTo("100.00");
        assertThat(summary.charged().byCategory().get("LODGING")).isEqualByComparingTo("100.00");
        assertThat(summary.charged().byCountry()).hasSize(1);
        assertThat(summary.charged().byCountry().get(0).amount()).isEqualByComparingTo("100.00");
        // Native totals are the share in the currency it was spent in.
        assertThat(summary.charged().nativeTotals().get(0).currency()).isEqualTo("USD");
        assertThat(summary.charged().nativeTotals().get(0).amount()).isEqualByComparingTo("110.00");
    }

    @Test
    void theForecastRollupDividesTheSameWayTheChargedOneDoes() {
        service.create(TRIP_ID, ALEX, new BudgetService.Input(
                "Tour deposit", ChecklistCategory.ACTIVITIES, new BigDecimal("200.00"), "EUR",
                null, null, List.of(ALEX, SAM), null, false));

        BudgetService.Summary summary = as(ALEX);

        assertThat(summary.charged().total()).isEqualByComparingTo("0.00");
        assertThat(summary.forecast().total()).isEqualByComparingTo("100.00");
    }

    // ── the plan form's sharers reach the row ───────────────────────────

    @Test
    void aPlansCostCarriesItsSharersOntoTheBudgetRowItCreates() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", true, List.of(ALEX, SAM), ALEX, List.of()));

        BudgetItem row = budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow();
        assertThat(row.getSharedByUserIds()).containsExactly(ALEX, SAM);
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("120.00");
        assertThat(as(RAY).charged().total()).isEqualByComparingTo("0.00");
    }

    @Test
    void changingAPlansSharersMovesTheCostBetweenBudgets() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", true, List.of(ALEX), ALEX, List.of()));
        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("240.00");

        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null,
                new BigDecimal("240.00"), "EUR", null, List.of(SAM), null, null));

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("0.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("240.00");
    }

    /** Absent means "the form said nothing", which must leave the row alone. */
    @Test
    void repricingAPlanWithoutSendingSharersLeavesThemAsTheyWere() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", true, List.of(SAM), ALEX, List.of()));

        itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, null, null, null, null,
                new BigDecimal("300.00"), "EUR", null, null, null, null));

        assertThat(budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow()
                .getSharedByUserIds()).containsExactly(SAM);
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("300.00");
    }

    // ── validation ──────────────────────────────────────────────────────

    @Test
    void sharingWithSomebodyWhoIsNotAMemberIsRejected() {
        assertThatThrownBy(() -> expense("Taxi", "100.00", List.of(ALEX, "user-nobody")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a member of this trip");
    }

    /**
     * The plan used to be written before its sharers were checked, so a stale
     * member list got a 400 back and still left the plan on the itinerary —
     * with no budget row behind its cost, invisible to every total.
     */
    @Test
    void aPlanSharedWithSomebodyWhoIsNotAMemberIsNotSavedAtAll() {
        assertThatThrownBy(() -> itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", true, List.of(ALEX, "user-nobody"), ALEX, List.of())))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a member of this trip");

        assertThat(itinerary.findAll(SLUG)).isEmpty();
        assertThat(budget.findAll(SLUG)).isEmpty();
    }

    /** The same on an edit: a rejected request changes nothing it came with. */
    @Test
    void anEditSharingWithSomebodyWhoIsNotAMemberChangesNothing() {
        ItineraryItem plan = itineraryService.create(TRIP_ID, ALEX, new ItineraryService.Input(
                null, ChecklistCategory.LODGING, "Hotel in Cusco",
                LocalDateTime.parse("2026-10-25T15:00"), null, null,
                new BigDecimal("240.00"), "EUR", true, List.of(ALEX), ALEX, List.of()));

        assertThatThrownBy(() -> itineraryService.update(TRIP_ID, ALEX, plan.getId(), new ItineraryService.Input(
                null, null, "Hotel in Cusco, renamed", null, null, null,
                new BigDecimal("300.00"), "EUR", null, List.of("user-nobody"), null, null)))
                .isInstanceOf(ApiException.class);

        ItineraryItem stored = itinerary.findById(SLUG, plan.getId()).orElseThrow();
        assertThat(stored.getDescription()).isEqualTo("Hotel in Cusco");
        assertThat(stored.getCost()).isEqualByComparingTo("240.00");
        assertThat(budget.findByItineraryItem(SLUG, plan.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("240.00");
    }

    @Test
    void namingTheSameMemberTwiceCountsThemOnce() {
        expense("Taxi", "100.00", List.of(ALEX, ALEX, SAM));

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("50.00");
    }

    @Test
    void clearingTheNamesOnAnExpenseHandsItBackToTheWholeTrip() {
        BudgetItem row = expense("Taxi", "90.00", List.of(ALEX));
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("0.00");

        service.update(TRIP_ID, ALEX, row.getId(), new BudgetService.Input(
                null, null, null, null, null, null, List.of(), null, null));

        assertThat(as(ALEX).charged().total()).isEqualByComparingTo("30.00");
        assertThat(as(SAM).charged().total()).isEqualByComparingTo("30.00");
    }
}
