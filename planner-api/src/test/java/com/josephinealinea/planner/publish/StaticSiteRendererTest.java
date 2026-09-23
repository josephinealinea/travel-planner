package com.josephinealinea.planner.publish;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.publish.api.PublishOptions;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.publish.api.StaticSiteRenderer;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.rates.TestRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        var destinations = new YamlDestinationRepository(store, paths, locks);
        var checklist = new YamlChecklistRepository(store, paths, locks);
        var itinerary = new YamlItineraryRepository(store, paths, locks);
        var budget = new YamlBudgetRepository(store, paths, locks);

        seed(destinations, checklist, itinerary, budget);

        // The renderer only ever summarises a Trip it is handed, so the access
        // service the budget summary would need for the by-id path is unused here.
        renderer = new StaticSiteRenderer(destinations, checklist, itinerary,
                new BudgetService(budget, itinerary, destinations,
                        new YamlUserRepository(store, paths, locks), new TripAccessService(null),
                        new TripCountries(destinations),
                        TestRates.with(store, paths, props,
                                Map.of("USD", new BigDecimal("1.17")))),
                new com.josephinealinea.planner.publish.infra.FileSystemPageStore(store, paths), props);
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
        lodging.setCountryCodes(java.util.List.of("dest-1"));
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

        // A three-night stay, stored the way ItineraryService now writes one:
        // an entry per day, the cost on check-in alone, no time on the nights
        // between. The renderer's job is to lay these out, not to expand them.
        itinerary.save(SLUG, night("plan-2", LocalDateTime.of(2026, 10, 25, 15, 0),
                false, new BigDecimal("240.00")));
        itinerary.save(SLUG, night("plan-2b", LocalDateTime.of(2026, 10, 26, 0, 0), true, null));
        itinerary.save(SLUG, night("plan-2c", LocalDateTime.of(2026, 10, 27, 0, 0), true, null));
        itinerary.save(SLUG, night("plan-2d", LocalDateTime.of(2026, 10, 28, 11, 0), false, null));

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

        BudgetItem vaccine = new BudgetItem();
        vaccine.setId("budget-2");
        vaccine.setTripId("trip-1");
        vaccine.setCategory(ChecklistCategory.OTHERS);
        vaccine.setDescription("Yellow fever vaccine");
        vaccine.setAmount(new BigDecimal("45.00"));
        vaccine.setCurrency("USD");
        vaccine.setDate(LocalDate.of(2026, 10, 20));
        budget.save(SLUG, vaccine);

        // One expense nobody has paid yet, so the charged and forecast rollups
        // differ by a known amount and a test can tell which one it is reading.
        BudgetItem deposit = new BudgetItem();
        deposit.setId("budget-3");
        deposit.setTripId("trip-1");
        deposit.setCategory(ChecklistCategory.ACTIVITIES);
        deposit.setDescription("Salt flats tour deposit");
        deposit.setAmount(new BigDecimal("100.00"));
        deposit.setCurrency("USD");
        deposit.setDate(LocalDate.of(2026, 10, 30));
        deposit.markCharged(false, java.time.Instant.now());
        budget.save(SLUG, deposit);
    }

    private static ItineraryItem night(String id, LocalDateTime startAt,
                                       boolean allDay, BigDecimal cost) {
        ItineraryItem item = new ItineraryItem();
        item.setId(id);
        item.setTripId("trip-1");
        item.setChecklistItemId("check-1");
        item.setCategory(ChecklistCategory.LODGING);
        item.setDescription("Hotel in Cusco — check-in 25 Oct, check-out 28 Oct");
        item.setStartAt(startAt);
        if (allDay) item.setAllDay(Boolean.TRUE);
        if (cost != null) {
            item.setCost(cost);
            item.setCurrency("USD");
        }
        return item;
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
        trip.setDisplayCurrency("EUR");
        return trip;
    }

    /** Costs off, matching the account default, unless a test says otherwise. */
    private String render(String theme) throws Exception {
        return render(theme, false);
    }

    private String render(String theme, boolean showItineraryCost) throws Exception {
        return render(theme, new PublishOptions(showItineraryCost, false, false));
    }

    private String render(String theme, PublishOptions options) throws Exception {
        renderer.render(trip(theme), options, theme);
        return Files.readString(publishDir.resolve(SLUG).resolve("index.html"));
    }

    /** The window.TRIP payload the rendered page carries, as JSON. */
    private JsonNode payload(String html) throws Exception {
        String marker = "window.TRIP = ";
        int start = html.indexOf(marker) + marker.length();
        // The payload escapes "</" as "<\/", so the first ";</script>" after it
        // is always the real terminator.
        int end = html.indexOf(";</script>", start);
        return new ObjectMapper().readTree(html.substring(start, end));
    }

    /** Every entry for one description, keyed by the day it appears on. */
    private Map<String, JsonNode> entriesByDay(JsonNode payload, String description) {
        Map<String, JsonNode> found = new LinkedHashMap<>();
        payload.get("days").forEach(day -> {
            for (JsonNode entry : day.get("entries")) {
                if (entry.get("description").asText().startsWith(description)) {
                    found.put(day.get("date").asText(), entry);
                }
            }
        });
        return found;
    }

    @Test
    void writesThePageAndItsDataFile() {
        renderer.render(trip("minima"), PublishOptions.hidden(), "minima");

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
        // plain static host with no API behind it. The one absolute URL allowed
        // is the brand's link to the site: an anchor is somewhere a reader may
        // go, not something the page loads. It is cut out by its exact text,
        // so any other link — or a second copy of this one — still fails.
        String rest = html.replace("<a href=\"http://localhost:3000\">Travelling Llama</a>", "");
        assertThat(rest).doesNotContain("src=\"http").doesNotContain("href=\"http");
        assertThat(rest).doesNotContain("localhost");
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
        assertThat(html).contains("%3Csvg").contains("%F0%9F%A6%99");
    }

    /**
     * A published page is the one screen of the app strangers see, so it says
     * where it came from — and links to the site, not to the API that wrote it.
     * The site is the first CORS origin, the same place the emails send people;
     * with none configured that is the local frontend.
     */
    @Test
    void signsThePageWithTheBrandLinkingToTheSite() throws Exception {
        String html = render("minima");

        assertThat(html).contains("Planned with")
                .contains("<a href=\"http://localhost:3000\">Travelling Llama</a>")
                .doesNotContain("href=\"http://localhost:8080");
    }

    /**
     * Asserted on the html element's own attribute, never with
     * {@code contains("data-theme=\"x\"")}.
     *
     * That substring form passed for the wrong reason and would have kept
     * passing for any theme at all: the page inlines publish/page.css, which
     * carries a {@code :root[data-theme="..."]} block for every theme it has
     * ever styled — including the two withdrawn ones. The match was finding the
     * stylesheet, not the attribute.
     */
    @Test
    void recordsTheThemeItWasPublishedIn() throws Exception {
        assertThat(themeOf(render("y2k"))).isEqualTo("y2k");
        assertThat(themeOf(render("dark"))).isEqualTo("dark");
    }

    /**
     * A theme that is not on offer falls back rather than emitting something
     * unstyled. Covers both an outright unknown name and the two that were
     * withdrawn from StaticSiteRenderer.THEMES — a trip published under one of
     * those before the removal still renders, as Minima.
     */
    @Test
    void aThemeThatIsNoLongerOfferedFallsBackToTheDefault() throws Exception {
        assertThat(themeOf(render("not-a-theme"))).isEqualTo("minima");
        assertThat(themeOf(render("manila"))).isEqualTo("minima");
        assertThat(themeOf(render("retro-game"))).isEqualTo("minima");
    }

    /**
     * The page carries the themes a reader may switch to, so page.js never
     * needs its own copy of the list — withdraw a theme from THEMES and newly
     * published pages stop offering it.
     */
    @Test
    void offersTheReaderOnlyTheThemesTheAppItselfOffers() throws Exception {
        String offered = themesOf(render("minima"));

        assertThat(offered.split(",")).containsExactly("minima", "y2k", "dark");
        // The withdrawn two still have palettes inlined — every :root block is
        // in page.css — but a reader must not be able to choose them.
        assertThat(offered).doesNotContain("manila").doesNotContain("retro-game");
    }

    /**
     * A reader's switcher starts from the theme the trip was published in, so
     * the published theme still has to reach the attribute even though it is no
     * longer the last word.
     */
    @Test
    void theOfferedListIsIndependentOfWhichThemeWasPublished() throws Exception {
        assertThat(themeOf(render("dark"))).isEqualTo("dark");
        assertThat(themesOf(render("dark"))).isEqualTo(themesOf(render("y2k")));
    }

    /** The data-themes on the html element: what the reader may switch to. */
    private static String themesOf(String html) {
        var matcher = java.util.regex.Pattern
                .compile("<html[^>]*\\sdata-themes=\"([^\"]*)\"")
                .matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Nights by default, days when the account asks — and never both, so
     * page.js has no flag to interpret and the payload carries only what is
     * shown. Cusco is 25-Oct to 31-Oct in this fixture: six nights, seven days
     * — the two counts differ by one, which is the whole reason the setting
     * exists.
     */
    @Test
    void aDestinationCountsNightsUnlessTheAccountAsksForDays() throws Exception {
        JsonNode nights = destination(payload(render("minima", PublishOptions.hidden())), "Cusco");
        assertThat(nights.get("nights").asLong()).isEqualTo(6);
        assertThat(nights.get("days").isNull()).isTrue();

        JsonNode days = destination(
                payload(render("minima", new PublishOptions(false, true, false))), "Cusco");
        assertThat(days.get("days").asLong()).isEqualTo(7);
        assertThat(days.get("nights").isNull()).isTrue();
    }

    private static JsonNode destination(JsonNode payload, String name) {
        for (JsonNode node : payload.get("destinations")) {
            if (name.equals(node.get("name").asText())) return node;
        }
        throw new AssertionError("no destination named " + name);
    }

    // ── the budget's forecast rollup ────────────────────────────────────

    /**
     * The account setting is off by default, and the page then carries no
     * forecast figure at all — null, the same way a destination's unused
     * nights/days half is left null. A published page is public, and what a
     * trip is still going to cost is a more private number than what it has
     * cost so far, so it is not shipped and then hidden.
     */
    @Test
    void withoutTheSettingThePageCarriesNoForecastRollupAtAll() throws Exception {
        JsonNode budget = payload(render("minima", PublishOptions.hidden())).get("budget");

        assertThat(budget.get("forecast").isNull()).isTrue();
        // 246.22 + 45.00 USD at 1.17 per EUR. The pending deposit is not in it.
        assertThat(budget.get("charged").get("total").decimalValue()).isEqualByComparingTo("248.90");
    }

    @Test
    void withTheSettingTheForecastRollupShipsAlongsideTheCharges() throws Exception {
        JsonNode budget = payload(render("minima", new PublishOptions(false, false, true)))
                .get("budget");

        assertThat(budget.get("charged").get("total").decimalValue()).isEqualByComparingTo("248.90");
        // The same two, plus the 100.00 USD deposit nobody has paid: 85.47 more.
        assertThat(budget.get("forecast").get("total").decimalValue()).isEqualByComparingTo("334.37");
    }

    /** The data-theme on the html element, which is the one that styles the page. */
    private static String themeOf(String html) {
        var matcher = java.util.regex.Pattern
                .compile("<html[^>]*\\sdata-theme=\"([^\"]*)\"")
                .matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Test
    void escapesTheTitleSoItCannotBreakOutOfTheMarkup() throws Exception {
        Trip trip = trip("minima");
        trip.setTitle("Trip <script>alert(1)</script> & co");
        renderer.render(trip, PublishOptions.hidden(), "minima");

        String html = Files.readString(publishDir.resolve(SLUG).resolve("index.html"));
        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).doesNotContain("<title>Trip <script>");
    }

    @Test
    void removeDeletesThePublishedDirectory() {
        renderer.render(trip("minima"), PublishOptions.hidden(), "minima");
        assertThat(publishDir.resolve(SLUG)).exists();

        renderer.remove(SLUG);
        assertThat(Files.exists(publishDir.resolve(SLUG))).isFalse();
    }

    @Test
    void neverLeaksAnIndividualExpenseDescription() throws Exception {
        String html = render("minima");

        // Per-category totals are what a published page should show; a
        // manual expense's own description must not reach the page at all —
        // not in the rendered markup, and not in the inlined window.TRIP
        // JSON either, since that is also part of index.html's text.
        assertThat(html).doesNotContain("Yellow fever vaccine");
    }

    @Test
    void aStayAppearsOnEveryDayFromCheckInToCheckOut() throws Exception {
        Map<String, JsonNode> stay = entriesByDay(payload(render("minima")), "Hotel in Cusco");

        // Four days for a three-night stay: you are still in the room on the
        // morning you check out. They are four stored entries now, so this
        // pins that the renderer places each on its own day and invents none.
        assertThat(stay.keySet())
                .containsExactly("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28");
    }

    @Test
    void aStayCarriesItsCostAndTimesOnlyWhereTheyApply() throws Exception {
        // Costs on, since the point here is where the one charge lands.
        Map<String, JsonNode> stay =
                entriesByDay(payload(render("minima", true)), "Hotel in Cusco");

        // Check-in day: the arrival time, and the one and only cost.
        assertThat(stay.get("2026-10-25").get("startTime").asText()).isEqualTo("15:00");
        assertThat(stay.get("2026-10-25").get("cost").asText()).isEqualTo("240.00");

        // A night in between is just "you are here" — no time, no second charge.
        assertThat(stay.get("2026-10-26").get("startTime").isNull()).isTrue();
        assertThat(stay.get("2026-10-26").get("cost").isNull()).isTrue();

        // Check-out day: the time you have to be out by, still no charge.
        assertThat(stay.get("2026-10-28").get("startTime").asText()).isEqualTo("11:00");
        assertThat(stay.get("2026-10-28").get("cost").isNull()).isTrue();

        // One booking, one charge, however many days it shows on.
        long charged = stay.values().stream().filter(entry -> !entry.get("cost").isNull()).count();
        assertThat(charged).isEqualTo(1);
    }

    /**
     * The account setting behind this is off by default, and "not displayed"
     * has to mean "not shipped": a published page is public, so a cost left in
     * window.TRIP is readable by anyone who opens the source even if nothing
     * renders it. So the assertion is about the payload, not the markup.
     */
    @Test
    void anItineraryCostIsAbsentFromThePayloadUnlessTheSettingIsOn() throws Exception {
        Map<String, JsonNode> withoutIt =
                entriesByDay(payload(render("minima", false)), "Hotel in Cusco");
        assertThat(withoutIt.get("2026-10-25").get("cost").isNull()).isTrue();
        assertThat(withoutIt.get("2026-10-25").get("currency").isNull()).isTrue();
        // The entry itself still shows, times and all — only the money goes.
        assertThat(withoutIt.get("2026-10-25").get("startTime").asText()).isEqualTo("15:00");

        // And the number never appears anywhere in the file, inlined JSON
        // included — the check the markup alone could not make.
        assertThat(render("minima", false)).doesNotContain("240.00");

        Map<String, JsonNode> withIt =
                entriesByDay(payload(render("minima", true)), "Hotel in Cusco");
        assertThat(withIt.get("2026-10-25").get("cost").asText()).isEqualTo("240.00");
    }

    @Test
    void anEventStillAppearsOnItsOwnDayAlone() throws Exception {
        // The seeded flight leaves on the 24th at 22:15 and lands on the 25th at
        // 08:40, so it would span two days the moment the rule leaked past
        // lodging. It belongs to the day it departs.
        Map<String, JsonNode> flight = entriesByDay(payload(render("minima")), "Delta flight");

        assertThat(flight.keySet()).containsExactly("2026-10-24");
        assertThat(flight.get("2026-10-24").get("startTime").asText()).isEqualTo("22:15");
        assertThat(flight.get("2026-10-24").get("endTime").asText()).isEqualTo("08:40");
    }
}
