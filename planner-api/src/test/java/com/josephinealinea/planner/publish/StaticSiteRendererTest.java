package com.josephinealinea.planner.publish;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.publish.api.StaticSiteRenderer;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The published page has to stand on its own once it is copied to a static
 * host, so these assertions are about self-containment as much as content.
 */
class StaticSiteRendererTest {

    private static final String SLUG = "latam-trip-2026";

    private StaticSiteRenderer renderer;
    private Path publishDir;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        publishDir = dir.resolve("published");

        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(publishDir.toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Bootstrap(null, null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        var destinations = new DestinationRepository(store, paths, locks);
        var checklist = new ChecklistRepository(store, paths, locks);
        var itinerary = new ItineraryRepository(store, paths, locks);
        var budget = new BudgetRepository(store, paths, locks);

        seed(destinations, checklist, itinerary, budget);

        // The renderer only ever summarises a Trip it is handed, so the access
        // service the budget summary would need for the by-id path is unused here.
        renderer = new StaticSiteRenderer(destinations, checklist, itinerary,
                new BudgetService(budget, itinerary, new TripAccessService(null)), store, paths);
    }

    private void seed(DestinationRepository destinations,
                      ChecklistRepository checklist,
                      ItineraryRepository itinerary,
                      BudgetRepository budget) {

        Destination cusco = new Destination();
        cusco.setId("dest-1");
        cusco.setTripId("trip-1");
        cusco.setName("Cusco");
        cusco.setCountryCode("PE");
        cusco.setCountryName("Peru");
        cusco.setCountryFlag("🇵🇪");
        cusco.setLatitude(-13.53188);
        cusco.setLongitude(-71.96701);
        cusco.setStartDate(LocalDate.of(2026, 10, 25));
        cusco.setEndDate(LocalDate.of(2026, 10, 31));
        destinations.save(SLUG, cusco);

        ChecklistItem lodging = new ChecklistItem();
        lodging.setId("check-1");
        lodging.setTripId("trip-1");
        lodging.setDestinationId("dest-1");
        lodging.setCategory(ChecklistCategory.LODGING);
        lodging.setDescription("Plan 6N accommodation in Cusco");
        lodging.setStatus(ChecklistStatus.TODO);
        checklist.save(SLUG, lodging);

        ChecklistItem visa = new ChecklistItem();
        visa.setId("check-2");
        visa.setTripId("trip-1");
        visa.setCategory(ChecklistCategory.OTHERS);
        visa.setDescription("US / UK Visa");
        visa.setStatus(ChecklistStatus.COMPLETED);
        visa.setCompletedAt(Instant.now());
        checklist.save(SLUG, visa);

        ItineraryItem flight = new ItineraryItem();
        flight.setId("plan-1");
        flight.setTripId("trip-1");
        flight.setChecklistItemId("check-1");
        flight.setCategory(ChecklistCategory.TRANSPORTATION);
        flight.setDescription("Delta flight (DL 6071) from LAX to LIM");
        flight.setStartAt(LocalDateTime.of(2026, 10, 24, 22, 15));
        flight.setEndAt(LocalDateTime.of(2026, 10, 25, 8, 40));
        flight.setCost(new BigDecimal("246.22"));
        flight.setCurrency("USD");
        itinerary.save(SLUG, flight);

        BudgetItem expense = new BudgetItem();
        expense.setId("budget-1");
        expense.setTripId("trip-1");
        expense.setItineraryItemId("plan-1");
        expense.setCategory(ChecklistCategory.TRANSPORTATION);
        expense.setDescription("Delta flight (DL 6071) from LAX to LIM");
        expense.setAmount(new BigDecimal("246.22"));
        expense.setCurrency("USD");
        expense.setDate(LocalDate.of(2026, 10, 24));
        budget.save(SLUG, expense);
    }

    private static Trip trip(String theme) {
        Trip trip = new Trip();
        trip.setId("trip-1");
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setStartDate(LocalDate.of(2026, 10, 24));
        trip.setEndDate(LocalDate.of(2026, 11, 8));
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setPublishedAt(Instant.now());
        trip.setPublishedTheme(theme);
        trip.setDisplayCurrency("EUR");
        trip.setExchangeRates(new java.util.LinkedHashMap<>(Map.of("USD", new BigDecimal("1.17"))));
        return trip;
    }

    private String render(String theme) throws Exception {
        renderer.render(trip(theme));
        return Files.readString(publishDir.resolve(SLUG).resolve("index.html"));
    }

    @Test
    void writesThePageAndItsDataFile() {
        renderer.render(trip("minima"));

        assertThat(publishDir.resolve(SLUG).resolve("index.html")).exists();
        assertThat(publishDir.resolve(SLUG).resolve("trip.json")).exists();
    }

    @Test
    void everyPlaceholderIsSubstituted() throws Exception {
        String html = render("minima");

        // Matched narrowly: the inlined JSON legitimately ends nested objects
        // with "}}", so only an actual {{name}} token counts as unsubstituted.
        assertThat(html).doesNotContainPattern("\\{\\{[a-zA-Z]+}}");
    }

    @Test
    void inlinesItsOwnStylesheetAndScriptWithNoExternalReferences() throws Exception {
        String html = render("minima");

        assertThat(html).contains("<style>").contains("--tp-bg");
        assertThat(html).contains("window.TRIP =");
        // Nothing may be fetched at view time; the page has to work from a
        // plain static host with no API behind it.
        assertThat(html).doesNotContain("src=\"http").doesNotContain("href=\"http");
        assertThat(html).doesNotContain("localhost");
    }

    @Test
    void carriesTheTripContent() throws Exception {
        String html = render("minima");

        assertThat(html).contains("LATAM Trip 2026");
        assertThat(html).contains("Plan 6N accommodation in Cusco");
        assertThat(html).contains("Delta flight (DL 6071) from LAX to LIM");
        assertThat(html).contains("Cusco");
        assertThat(html).contains("US / UK Visa");
    }

    @Test
    void keepsThePercentEncodedFaviconIntact() throws Exception {
        String html = render("minima");

        // This is what a format string would have destroyed: the data URI's
        // percent escapes must survive assembly untouched.
        assertThat(html).contains("%3Csvg").contains("%F0%9F%A7%AD");
    }

    @Test
    void recordsTheThemeItWasPublishedIn() throws Exception {
        assertThat(render("manila")).contains("data-theme=\"manila\"");
        assertThat(render("retro-game")).contains("data-theme=\"retro-game\"");
        // An unknown theme falls back rather than emitting something unstyled.
        assertThat(render("not-a-theme")).contains("data-theme=\"minima\"");
    }

    @Test
    void escapesTheTitleSoItCannotBreakOutOfTheMarkup() throws Exception {
        Trip trip = trip("minima");
        trip.setTitle("Trip <script>alert(1)</script> & co");
        renderer.render(trip);

        String html = Files.readString(publishDir.resolve(SLUG).resolve("index.html"));
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<title>Trip <script>");
    }

    @Test
    void removeDeletesThePublishedDirectory() {
        renderer.render(trip("minima"));
        assertThat(publishDir.resolve(SLUG)).exists();

        renderer.remove(SLUG);
        assertThat(Files.exists(publishDir.resolve(SLUG))).isFalse();
    }
}
