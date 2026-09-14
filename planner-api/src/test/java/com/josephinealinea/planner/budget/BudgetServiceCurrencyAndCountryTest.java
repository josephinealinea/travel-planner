package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.rates.ExchangeRatesClient;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Instant;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The totals rollup now converts into the signed-in user's own display
 * currency preference rather than the trip's own anchor, pivoting through
 * that anchor when the two differ — and the same rollup carries a breakdown
 * by country alongside the existing one by category. Both are exercised here
 * against the real YAML-backed repositories, the same way BudgetSyncTest
 * exercises the cascade.
 */
class BudgetServiceCurrencyAndCountryTest {

    private static final String SLUG = "latam-trip-2026";

    private BudgetRepository budget;
    private DestinationRepository destinations;
    private RatesRepository rates;
    private YamlStore store;
    private YamlPaths paths;
    private AppProperties props;
    private BudgetService service;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        store = new YamlStore();
        paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        budget = new BudgetRepository(store, paths, locks);
        destinations = new DestinationRepository(store, paths, locks);
        ItineraryRepository itinerary = new ItineraryRepository(store, paths, locks);
        YamlUserRepository users = new YamlUserRepository(store, paths, locks);

        rates = new RatesRepository(store, paths);
        service = new BudgetService(budget, itinerary, destinations, users, new TripAccessService(null),
                new TripCountries(destinations),
                new RatesService(new ExchangeRatesClient(RestClient.create(), props), rates));
    }

    /**
     * Builds the trip and installs the rates it should be converted with.
     *
     * Rates used to live on the Trip, so a test set them there. They are now
     * one table for the install, and this writes it — the service reads it
     * lazily on the first summarise, and setUp gives every test its own, so
     * each test still gets exactly the rates it asks for.
     *
     * Every test here anchors the trip on EUR, which is also the table's base,
     * so the pivot and the trip currency coincide exactly as they did when the
     * two were the same variable.
     */
    private Trip trip(String anchorCurrency, Map<String, BigDecimal> perEuro) {
        RateTable table = new RateTable();
        table.setBase("EUR");
        table.setFetchedAt(Instant.parse("2026-09-14T01:30:00Z"));
        Map<String, BigDecimal> all = new LinkedHashMap<>();
        all.put("EUR", BigDecimal.ONE);
        all.putAll(perEuro);
        table.setRates(all);
        rates.save(table);

        Trip trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setDisplayCurrency(anchorCurrency);
        return trip;
    }

    private static User userWithDisplayCurrency(String currency) {
        User user = new User();
        user.setId("user-1");
        user.setDisplayCurrency(currency);
        return user;
    }

    private BudgetItem expense(String id, BigDecimal amount, String currency, ChecklistCategory category,
                               List<String> countryCodes) {
        BudgetItem item = new BudgetItem();
        item.setId(id);
        item.setTripId("trip-1");
        item.setCategory(category);
        item.setDescription(id);
        item.setAmount(amount);
        item.setCurrency(currency);
        if (countryCodes != null) item.setCountryCodes(countryCodes);
        return budget.save(SLUG, item);
    }

    private Destination destination(String id, String countryCode, String countryName, String flag) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId("trip-1");
        destination.setName(id);
        destination.setCountryCode(countryCode);
        destination.setCountryName(countryName);
        destination.setCountryFlag(flag);
        return destinations.save(SLUG, destination);
    }

    // ── currency pivot ──────────────────────────────────────────────────

    @Test
    void withNoUserTotalsShowInTheTripsOwnAnchorCurrencyUnchanged() {
        Trip trip = trip("EUR", Map.of("USD", new BigDecimal("1.10")));
        expense("e1", new BigDecimal("110.00"), "USD", ChecklistCategory.OTHERS, null);

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.displayCurrency()).isEqualTo("EUR");
        assertThat(summary.totalsCurrency()).isEqualTo("EUR");
        assertThat(summary.total()).isEqualByComparingTo("100.00");
    }

    @Test
    void aUserWithNoDisplayCurrencySetFallsBackToTheTripsAnchor() {
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("50.00"), "EUR", ChecklistCategory.OTHERS, null);
        User user = new User();
        user.setId("user-1");

        BudgetService.Summary summary = service.summarise(trip, user);

        assertThat(summary.totalsCurrency()).isEqualTo("EUR");
        assertThat(summary.total()).isEqualByComparingTo("50.00");
    }

    @Test
    void anItemAlreadyInTheTargetCurrencyNeedsNoRateAtAll() {
        // Anchor is EUR with no rates configured at all, but the item is
        // already in the user's own GBP — nothing to convert, so nothing to
        // look up.
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("75.00"), "GBP", ChecklistCategory.OTHERS, null);
        User user = userWithDisplayCurrency("GBP");

        BudgetService.Summary summary = service.summarise(trip, user);

        assertThat(summary.total()).isEqualByComparingTo("75.00");
        assertThat(summary.currenciesMissingRates()).isEmpty();
    }

    @Test
    void anItemInTheAnchorCurrencyConvertsThroughTheSecondLegOnly() {
        // 1 EUR (anchor) = 0.85 GBP.
        Trip trip = trip("EUR", Map.of("GBP", new BigDecimal("0.85")));
        expense("e1", new BigDecimal("100.00"), "EUR", ChecklistCategory.OTHERS, null);
        User user = userWithDisplayCurrency("GBP");

        BudgetService.Summary summary = service.summarise(trip, user);

        assertThat(summary.total()).isEqualByComparingTo("85.00");
    }

    @Test
    void aThirdCurrencyPivotsThroughTheAnchorInBothLegs() {
        // 1 EUR (anchor) = 1.10 USD, 1 EUR = 0.85 GBP.
        // 110 USD -> 100 EUR -> 85 GBP.
        Trip trip = trip("EUR", Map.of("USD", new BigDecimal("1.10"), "GBP", new BigDecimal("0.85")));
        expense("e1", new BigDecimal("110.00"), "USD", ChecklistCategory.OTHERS, null);
        User user = userWithDisplayCurrency("GBP");

        BudgetService.Summary summary = service.summarise(trip, user);

        assertThat(summary.total()).isEqualByComparingTo("85.00");
    }

    @Test
    void aMissingRateForTheUsersOwnCurrencyExcludesTheRowAndReportsThatCurrency() {
        // The item's own currency (EUR = anchor) needs no rate for the first
        // leg, but there is no GBP rate at all — the second leg has nothing
        // to pivot on.
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("100.00"), "EUR", ChecklistCategory.OTHERS, null);
        User user = userWithDisplayCurrency("GBP");

        BudgetService.Summary summary = service.summarise(trip, user);

        assertThat(summary.total()).isEqualByComparingTo("0.00");
        assertThat(summary.currenciesMissingRates()).containsExactly("GBP");
    }

    // ── country breakdown ───────────────────────────────────────────────

    @Test
    void aRowWithNoDestinationIsGroupedUnderNoLocation() {
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("40.00"), "EUR", ChecklistCategory.OTHERS, List.of());

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.byCountry()).hasSize(1);
        assertThat(summary.byCountry().get(0).key()).isEqualTo("NO_LOCATION");
        assertThat(summary.byCountry().get(0).amount()).isEqualByComparingTo("40.00");
    }

    @Test
    void aRowLinkedToOneCountryIsGroupedUnderIt() {
        destination("dest-1", "PE", "Peru", "🇵🇪");
        destination("dest-2", "PE", "Peru", "🇵🇪");
        Trip trip = trip("EUR", Map.of());
        // Two Peruvian cities on the trip, but the row links to Peru once —
        // the name and flag on the slice are borrowed from either of them.
        expense("e1", new BigDecimal("60.00"), "EUR", ChecklistCategory.LODGING,
                List.of("PE"));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.byCountry()).hasSize(1);
        assertThat(summary.byCountry().get(0).key()).isEqualTo("PE");
        assertThat(summary.byCountry().get(0).name()).isEqualTo("Peru");
        assertThat(summary.byCountry().get(0).amount()).isEqualByComparingTo("60.00");
    }

    @Test
    void aRowSpanningTwoCountriesSplitsEvenlyBetweenThem() {
        destination("dest-1", "PE", "Peru", "🇵🇪");
        destination("dest-2", "BO", "Bolivia", "🇧🇴");
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("30.00"), "EUR", ChecklistCategory.TRANSPORTATION,
                List.of("PE", "BO"));

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.byCountry()).hasSize(2);
        assertThat(summary.byCountry()).allSatisfy(
                slice -> assertThat(slice.amount()).isEqualByComparingTo("15.00"));
        assertThat(summary.byCountry()).extracting(BudgetService.CountryAmount::key)
                .containsExactlyInAnyOrder("PE", "BO");
    }

    @Test
    void theSplitIsPerCountryHoweverManyCitiesTheTripHasInEach() {
        destination("dest-1", "FR", "France", "🇫🇷");
        destination("dest-2", "FR", "France", "🇫🇷");
        destination("dest-3", "BE", "Belgium", "🇧🇪");
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("90.00"), "EUR", ChecklistCategory.LODGING,
                List.of("FR", "BE"));

        BudgetService.Summary summary = service.summarise(trip);

        // Half each, and deliberately not two thirds to France for having two
        // cities on the trip. The row says it was spent across France and
        // Belgium; how many French stops the itinerary happens to contain is
        // not a statement about where the money went. When the link was a city
        // the split was per destination and France did carry two thirds — that
        // weighting went with the link, and a country cannot be named twice.
        assertThat(summary.byCountry()).hasSize(2);
        assertThat(slice(summary, "FR")).isEqualByComparingTo("45.00");
        assertThat(slice(summary, "BE")).isEqualByComparingTo("45.00");
    }

    @Test
    void anAmountThatWillNotDivideEvenlyStillAddsBackUpToTheWhole() {
        destination("dest-1", "PE", "Peru", "🇵🇪");
        destination("dest-2", "BO", "Bolivia", "🇧🇴");
        destination("dest-3", "CL", "Chile", "🇨🇱");
        Trip trip = trip("EUR", Map.of());
        // 10.00 into three parts is 3.333...; the cents have to land somewhere.
        expense("e1", new BigDecimal("10.00"), "EUR", ChecklistCategory.OTHERS,
                List.of("dest-1", "dest-2", "dest-3"));

        BudgetService.Summary summary = service.summarise(trip);

        BigDecimal summed = summary.byCountry().stream()
                .map(BudgetService.CountryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summed).isEqualByComparingTo("10.00");
        assertThat(summed).isEqualByComparingTo(summary.total());
        // No slice is silently rounded away.
        assertThat(summary.byCountry()).hasSize(3);
    }

    private static BigDecimal slice(BudgetService.Summary summary, String key) {
        return summary.byCountry().stream()
                .filter(c -> c.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no slice for " + key))
                .amount();
    }

    @Test
    void theCountrySlicesSumToTheSameTotalAsTheCategorySlices() {
        destination("dest-1", "PE", "Peru", "🇵🇪");
        destination("dest-2", "BO", "Bolivia", "🇧🇴");
        Trip trip = trip("EUR", Map.of("USD", new BigDecimal("1.10")));
        expense("e1", new BigDecimal("20.00"), "EUR", ChecklistCategory.OTHERS, null); // no location
        expense("e2", new BigDecimal("60.00"), "EUR", ChecklistCategory.LODGING, List.of("dest-1")); // Peru
        expense("e3", new BigDecimal("30.00"), "EUR", ChecklistCategory.TRANSPORTATION,
                List.of("dest-1", "dest-2")); // Multiple
        // In a currency with no rate: excluded from both breakdowns and the total.
        expense("e4", new BigDecimal("15.00"), "GBP", ChecklistCategory.OTHERS, List.of("dest-1"));

        BudgetService.Summary summary = service.summarise(trip);

        BigDecimal countrySum = summary.byCountry().stream()
                .map(BudgetService.CountryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal categorySum = summary.byCategory().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(summary.currenciesMissingRates()).containsExactly("GBP");
        assertThat(summary.total()).isEqualByComparingTo("110.00");
        assertThat(countrySum).isEqualByComparingTo(summary.total());
        assertThat(categorySum).isEqualByComparingTo(summary.total());
        assertThat(summary.byCountry()).hasSize(3);
    }

    // ── native totals ──────────────────────────────────────────────────

    @Test
    void nativeTotalsSumTheRawAmountPerCurrencyUnconverted() {
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("20.00"), "EUR", ChecklistCategory.OTHERS, null);
        expense("e2", new BigDecimal("30.00"), "EUR", ChecklistCategory.LODGING, null);
        expense("e3", new BigDecimal("15.00"), "USD", ChecklistCategory.TRANSPORTATION, null);

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(nativeAmount(summary, "EUR")).isEqualByComparingTo("50.00");
        assertThat(nativeAmount(summary, "USD")).isEqualByComparingTo("15.00");
    }

    @Test
    void aMissingRateExcludesAnItemFromTheTotalButNotFromItsNativeTotal() {
        // Converting is what a missing rate blocks — knowing what was spent,
        // in the currency it was spent in, needs no rate at all.
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("15.00"), "GBP", ChecklistCategory.OTHERS, null);

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.currenciesMissingRates()).containsExactly("GBP");
        assertThat(summary.total()).isEqualByComparingTo("0.00");
        assertThat(summary.nativeTotals()).hasSize(1);
        assertThat(nativeAmount(summary, "GBP")).isEqualByComparingTo("15.00");
    }

    @Test
    void nativeTotalsOrderLargestConvertedContributionFirst() {
        Trip trip = trip("EUR", Map.of("USD", new BigDecimal("1.10")));
        expense("e1", new BigDecimal("50.00"), "EUR", ChecklistCategory.OTHERS, null);
        // 100 USD / 1.10 =~ 90.91 EUR — bigger than the 50 EUR row above.
        expense("e2", new BigDecimal("100.00"), "USD", ChecklistCategory.LODGING, null);
        // No rate for JPY: sorts after both convertible currencies.
        expense("e3", new BigDecimal("500.00"), "JPY", ChecklistCategory.TRANSPORTATION, null);

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(summary.nativeTotals().stream().map(BudgetService.NativeAmount::currency).toList())
                .containsExactly("USD", "EUR", "JPY");
    }

    @Test
    void anItemWithNoLocationStillCountsTowardItsNativeTotal() {
        // Confirms nativeTotals is independent of destinationIds entirely —
        // it groups by currency alone, unlike byCountry.
        Trip trip = trip("EUR", Map.of());
        expense("e1", new BigDecimal("40.00"), "EUR", ChecklistCategory.OTHERS, null);

        BudgetService.Summary summary = service.summarise(trip);

        assertThat(nativeAmount(summary, "EUR")).isEqualByComparingTo("40.00");
    }

    private static BigDecimal nativeAmount(BudgetService.Summary summary, String currency) {
        return summary.nativeTotals().stream()
                .filter(n -> n.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no native total for " + currency))
                .amount();
    }
}
