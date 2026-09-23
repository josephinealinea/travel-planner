package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.publish.infra.PageStore;
import com.josephinealinea.planner.publish.infra.PageStore.Area;
import com.josephinealinea.planner.publish.infra.PageStore.PageFile;
import com.josephinealinea.planner.trips.api.Travellers;
import com.josephinealinea.planner.trips.domain.Trip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Renders a published trip to a single self-contained HTML file.
 *
 * Everything is inlined — stylesheet, script and the trip data as JSON — so the
 * output directory can be uploaded to any static host and works with no API
 * behind it. That is what makes the "put it on a CDN" step a plain file copy
 * rather than a deployment.
 *
 * The page's CSS and JS live in this module's resources rather than being
 * borrowed from the frontend build, so publishing never depends on the
 * frontend having been compiled.
 */
@Component
public class StaticSiteRenderer {

    private static final Logger log = LoggerFactory.getLogger(StaticSiteRenderer.class);

    /**
     * Must match the theme names the frontend's theme registry offers.
     *
     * Retro-Game and Manila were dropped from both. Their :root[data-theme]
     * blocks are still in publish/page.css — kept deliberately so re-adding
     * either needs no restyling — but with the names gone from here nothing can
     * ever be published under them, so those blocks are inert. safeTheme below
     * is what makes the removal safe for a trip already published in one.
     */
    private static final Set<String> THEMES = Set.of("minima", "y2k", "dark");

    /**
     * Display order for the reader's theme switcher. Lists every theme
     * page.css can style, including the two withdrawn ones, so re-offering one
     * is a single edit to THEMES above — this order never needs touching.
     */
    private static final List<String> THEME_ORDER =
            List.of("minima", "y2k", "dark", "retro-game", "manila");
    private static final String DEFAULT_THEME = "minima";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final BudgetService budgets;
    private final PageStore pages;
    private final ObjectMapper json;
    /** Where the "Planned with Travelling Llama" line links: the website, not the API. */
    private final String siteUrl;

    public StaticSiteRenderer(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetService budgets,
                              PageStore pages,
                              AppProperties props) {
        this.siteUrl = props.cors().siteUrl();
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budgets = budgets;
        this.pages = pages;
        this.json = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    /**
     * The themes on offer, in a fixed order — THEMES is a Set, and a page's
     * switcher must not reshuffle itself between publishes.
     */
    private static List<String> offeredThemes() {
        return THEME_ORDER.stream().filter(THEMES::contains).toList();
    }

    public static String safeTheme(String theme) {
        return THEMES.contains(theme) ? theme : DEFAULT_THEME;
    }

    /** Writes index.html and trip.json into the trip's publish directory. */
    /**
     * @param options the publishing member's own account settings. Anything
     *   switched off is left out of the payload entirely rather than hidden in
     *   the page, because a published page is public: a value still in
     *   window.TRIP is readable by anybody who opens the source, so "not
     *   displayed" has to mean "not shipped".
     */
    public void render(Trip trip, PublishOptions options) {
        render(trip, options, List.of());
    }

    /**
     * The trip's page, plus one page per member who asked for their own.
     *
     * A personal page is the same page with one thing different: its budget is
     * that member's share of each expense rather than the trip's whole spend.
     * That is the only way to answer "show me only my budget" on something
     * static — a published page has no sign-in and cannot know who is reading
     * it, so whose money it shows has to be decided when the file is written.
     *
     * They are written <i>inside</i> the trip's own directory, so a page can
     * never outlive the trip it belongs to. See YamlPaths.publishedMemberPage.
     */
    public void render(Trip trip, PublishOptions options, List<PersonalPage> personal) {
        var theme = safeTheme(trip.getPublishedTheme());
        write(trip, options, theme, Area.PUBLISHED, null, null);
        writePersonal(trip, theme, Area.PUBLISHED, personal);
        log.info("Published \"{}\"{}", trip.getTitle(),
                personal.isEmpty() ? "" : " with " + personal.size() + " personal page(s)");
    }

    /**
     * Renders to the staging directory instead, for a publish request the
     * owner has not decided yet.
     *
     * Rendered now, with the requesting member's own theme and account
     * settings, and moved into place untouched when the request is approved —
     * so what goes public is exactly what they asked to publish. Nothing in
     * the public directory until then; see YamlPaths.pendingDir.
     */
    public void renderPending(Trip trip, PublishOptions options, String theme) {
        renderPending(trip, options, theme, List.of());
    }

    public void renderPending(Trip trip, PublishOptions options, String theme,
                              List<PersonalPage> personal) {
        write(trip, options, safeTheme(theme), Area.PENDING, null, null);
        writePersonal(trip, safeTheme(theme), Area.PENDING, personal);
        log.info("Staged \"{}\" for approval", trip.getTitle());
    }

    /**
     * Writes the personal pages, after throwing away whatever was there.
     *
     * The clear-out is the load-bearing half. Publishing again rewrites
     * index.html in place, so without it a member who has since <i>un</i>ticked
     * their box would keep the page they asked for months ago, serving their
     * spending at a URL they believe they turned off — the same failure as a
     * published page outliving its trip, and just as silent.
     */
    private void writePersonal(Trip trip, String theme, Area area,
                               List<PersonalPage> personal) {
        pages.clearMemberPages(area, trip.getSlug());
        for (PersonalPage page : personal) {
            write(trip, page.options(), theme, area, page.memberSlug(), page.member());
        }
    }

    /**
     * One member's personal page: who it is for, the directory name it gets,
     * and their own account settings — it is their page, so what it reveals is
     * their choice rather than the publishing member's.
     */
    public record PersonalPage(com.josephinealinea.planner.identity.domain.User member,
                               String memberSlug,
                               PublishOptions options) {}

    /**
     * @param viewer whose budget the page shows, or null for the trip's own
     *   page. This is the whole of what makes a personal page personal: the
     *   summary is computed for that member, so every figure on it — total,
     *   slices, native totals — is their share and nobody else's appears at
     *   all. Not filtered in the page: a value left in window.TRIP is readable
     *   by anyone who opens the source, so another member's spending must not
     *   be in the file to begin with.
     */
    private void write(Trip trip, PublishOptions options, String theme, Area area,
                       String memberSlug,
                       com.josephinealinea.planner.identity.domain.User viewer) {
        PublishedTrip snapshot = snapshot(trip, options, viewer);
        String payload = writeJson(snapshot);
        pages.write(area, trip.getSlug(), memberSlug, PageFile.INDEX,
                page(trip, snapshot, payload, theme));
        pages.write(area, trip.getSlug(), memberSlug, PageFile.DATA, payload);
    }

    /**
     * Moves an approved staged page into the public directory, replacing
     * whatever was there.
     *
     * A move rather than a re-render: the page was built when the request was
     * sent, and approving it is not an invitation to rebuild it from data that
     * may have changed since. Returns false when nothing was staged, which is
     * how the caller knows to fall back to rendering.
     */
    public boolean promotePending(String slug) {
        // The store keeps the safe ordering: the old live page goes first, so
        // a failure part-way leaves no page rather than a half-merged one.
        return pages.promote(slug);
    }

    /** Throws the staged page away — a request that was rejected or withdrawn. */
    public void removePending(String slug) {
        pages.remove(Area.PENDING, slug);
    }

    /** The staged page's HTML, for the members-only preview. Null if none. */
    public String readPending(String slug) {
        return pages.read(Area.PENDING, slug, null, PageFile.INDEX).orElse(null);
    }

    public void remove(String slug) {
        pages.remove(Area.PUBLISHED, slug);
    }

    // ── snapshot ────────────────────────────────────────────────────────────

    private PublishedTrip snapshot(Trip trip, PublishOptions options,
                                   com.josephinealinea.planner.identity.domain.User viewer) {
        String slug = trip.getSlug();
        var tripDestinations = destinations.findAllOrdered(slug);
        var tripChecklist = checklist.findAllOrdered(slug);
        var tripItinerary = itinerary.findAllOrdered(slug);
        // A personal page lists only its viewer's parts, and leaves the rest
        // out of the file rather than hiding it: see Travellers, and the
        // "not displayed means not shipped" rule this page already keeps.
        Travellers travellers = Travellers.of(trip, tripDestinations, tripChecklist, tripItinerary);
        String viewerId = viewer == null ? null : viewer.getId();
        var allDestinations = viewerId == null ? tripDestinations : tripDestinations.stream()
                .filter(d -> Travellers.includes(travellers.ofDestination(d), viewerId)).toList();
        var allChecklist = viewerId == null ? tripChecklist : tripChecklist.stream()
                .filter(c -> Travellers.includes(travellers.ofChecklistItem(c), viewerId)).toList();
        var allItinerary = viewerId == null ? tripItinerary : tripItinerary.stream()
                .filter(i -> Travellers.includes(travellers.ofItineraryItem(i), viewerId)).toList();
        // With a viewer, every figure below is their share; without one it is
        // what the trip cost. See BudgetService.summarise.
        BudgetService.Summary budget = budgets.summarise(trip, viewer);

        // Countries in route order, de-duplicated, for the flag strip.
        Map<String, String> countries = new LinkedHashMap<>();
        allDestinations.forEach(destination -> {
            if (destination.getCountryName() != null) {
                countries.putIfAbsent(destination.getCountryName(),
                        destination.getCountryFlag() == null ? "" : destination.getCountryFlag());
            }
        });

        // "Tallinn -> Los Angeles -> Cusco", collapsing repeats.
        List<String> route = new ArrayList<>(new LinkedHashSet<>(
                allDestinations.stream().map(d -> d.getName()).toList()));

        // Items link to countries, so the chips they show are country names —
        // flag included, which is what the destination cards already show.
        Map<String, String> countryNames = new LinkedHashMap<>();
        allDestinations.forEach(destination -> {
            String code = destination.getCountryCode();
            if (code == null || code.isBlank()) return;
            String label = destination.getCountryName() == null
                    ? code : destination.getCountryName();
            countryNames.putIfAbsent(code.toUpperCase(),
                    destination.getCountryFlag() == null
                            ? label : destination.getCountryFlag() + " " + label);
        });

        return new PublishedTrip(
                trip.getTitle(),
                slug,
                iso(trip.getStartDate()),
                iso(trip.getEndDate()),
                trip.getPublishedAt() == null ? null : trip.getPublishedAt().toString(),
                countries.entrySet().stream()
                        .map(e -> new PublishedTrip.Country(e.getKey(), e.getValue()))
                        .toList(),
                String.join(" → ", route),
                allDestinations.stream().map(d -> toView(d, options)).toList(),
                days(allItinerary, allDestinations, options.itineraryCost()),
                allChecklist.stream()
                        .map(item -> toView(item, countryNames))
                        .toList(),
                toView(budget, options));
    }

    private PublishedTrip.Destination toView(
            com.josephinealinea.planner.destinations.domain.Destination destination,
            PublishOptions options) {
        String mapUrl = destination.hasCoordinates()
                ? "https://www.google.com/maps/search/?api=1&query=%s,%s"
                        .formatted(destination.getLatitude(), destination.getLongitude())
                : null;
        return new PublishedTrip.Destination(
                destination.getName(),
                destination.getCountryName(),
                destination.getCountryFlag(),
                destination.getLatitude(),
                destination.getLongitude(),
                iso(destination.getStartDate()),
                iso(destination.getEndDate()),
                // Nights unless the account asked for days, and only ever
                // one of them — see PublishedTrip.Destination.
                options.destinationDays() ? null : destination.nights(),
                options.destinationDays() ? destination.days() : null,
                destination.getNote(),
                mapUrl);
    }

    private PublishedTrip.Checklist toView(ChecklistItem item, Map<String, String> countryNames) {
        List<String> names = item.getCountryCodes().stream()
                .map(code -> countryNames.getOrDefault(code, code))
                .toList();
        return new PublishedTrip.Checklist(
                item.getCategory().dataKey(),
                item.getCategory().label(),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                item.getNote(),
                item.isCompleted() ? "done" : "todo",
                item.isCompleted() ? PublishStyle.DONE_ICON : PublishStyle.TODO_ICON,
                names);
    }

    /** Groups itinerary entries by day; undated plans are left out of the timeline. */
    private List<PublishedTrip.Day> days(List<ItineraryItem> items,
                                        List<Destination> allDestinations,
                                        boolean showItineraryCost) {
        // Sorted by day rather than by insertion. The stream above already
        // walks items in start order and a stay only ever adds days forward
        // from its own first, so insertion order happens to come out
        // chronological too — but only as a consequence of that upstream sort.
        // Ordering the map itself is what stops the page's day order from
        // silently depending on it.
        Map<LocalDate, List<PublishedTrip.Entry>> byDay = new TreeMap<>();

        // One entry per item, on its own day. A stay covering several nights is
        // already several items by the time it gets here — ItineraryService
        // writes them at create time — so expanding again would show each
        // night twice.
        items.stream()
                .filter(item -> item.getStartAt() != null)
                .sorted(Comparator.comparing(ItineraryItem::getStartAt))
                .forEach(item -> byDay.computeIfAbsent(item.getStartAt().toLocalDate(),
                                key -> new ArrayList<>())
                        .add(entryFor(item, showItineraryCost)));

        // Where the trip is on each day, from the destinations' own dates —
        // both ends counted, the same reading as the app's Days column. This is
        // also what adds days the itinerary has nothing planned on: a published
        // trip should still show that you were in Cusco on the 29th, and that
        // is the day the reader's browser looks the weather up for.
        Map<LocalDate, List<PublishedTrip.Place>> placesByDay = new TreeMap<>();
        for (Destination destination : allDestinations) {
            LocalDate from = destination.getStartDate();
            LocalDate to = destination.getEndDate();
            if (from == null || to == null || to.isBefore(from)) continue;
            PublishedTrip.Place place = new PublishedTrip.Place(
                    destination.getName(),
                    destination.getCountryName(),
                    destination.getCountryFlag(),
                    destination.getLatitude(),
                    destination.getLongitude());
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                placesByDay.computeIfAbsent(day, key -> new ArrayList<>()).add(place);
            }
        }

        List<LocalDate> allDays = new ArrayList<>(new TreeSet<>(
                Stream.concat(byDay.keySet().stream(), placesByDay.keySet().stream()).toList()));

        return allDays.stream()
                .map(day -> new PublishedTrip.Day(
                        iso(day),
                        byDay.getOrDefault(day, List.of()),
                        placesByDay.getOrDefault(day, List.of())))
                .toList();
    }

    /**
     * One entry's view.
     *
     * A night in the middle of a stay belongs to a day but not to an hour, so
     * it shows no time; the check-in and check-out entries show theirs. The
     * cost rides on the check-in entry alone, which is the only one that
     * carries it — one booking, one charge.
     */
    private PublishedTrip.Entry entryFor(ItineraryItem item, boolean showItineraryCost) {
        LocalTime shown = item.coversWholeDay() ? null : item.getStartAt().toLocalTime();
        LocalTime until = item.coversWholeDay() || item.getEndAt() == null
                ? null
                : item.getEndAt().toLocalTime();

        return new PublishedTrip.Entry(
                item.getCategory().dataKey(),
                item.getCategory().label(),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                time(shown),
                time(until),
                // Off by default, and omitted rather than hidden — see render().
                showItineraryCost && item.getCost() != null
                        ? item.getCost().toPlainString() : null,
                showItineraryCost ? item.getCurrency() : null);
    }

    private PublishedTrip.Budget toView(BudgetService.Summary summary, PublishOptions options) {
        return new PublishedTrip.Budget(
                // The currency the figures below are actually in, which is not
                // the trip's own anchor. The two coincide on the trip's page
                // and come apart on a personal one: a member's totals are
                // converted into whatever display currency they keep, so
                // labelling them with the trip's anchor would put "EUR" under
                // a column of SGD.
                summary.totalsCurrency(),
                toView(summary.charged()),
                // Off by default, and left null rather than shipped and then
                // hidden — see render().
                options.forecastExpenses() ? toView(summary.forecast()) : null);
    }

    private PublishedTrip.Budget.Breakdown toView(BudgetService.Breakdown breakdown) {
        List<PublishedTrip.Budget.Category> categories = new ArrayList<>();
        breakdown.byCategory().forEach((key, amount) -> {
            if (amount.signum() == 0) return;
            ChecklistCategory category = ChecklistCategory.valueOf(key);
            categories.add(new PublishedTrip.Budget.Category(
                    category.dataKey(),
                    category.label(),
                    PublishStyle.icon(category),
                    PublishStyle.color(category),
                    amount));
        });

        List<PublishedTrip.Budget.Country> countries = breakdown.byCountry().stream()
                .filter(country -> country.amount() != null && country.amount().signum() != 0)
                .map(country -> new PublishedTrip.Budget.Country(
                        country.key(), country.name(), country.flag(), country.amount()))
                .toList();

        List<PublishedTrip.Budget.Native> nativeTotals = breakdown.nativeTotals().stream()
                .map(n -> new PublishedTrip.Budget.Native(n.currency(), n.amount()))
                .toList();

        return new PublishedTrip.Budget.Breakdown(
                breakdown.total(),
                categories,
                countries,
                nativeTotals,
                breakdown.currenciesMissingRates());
    }

    // ── page assembly ───────────────────────────────────────────────────────

    /**
     * Assembles the page by substituting named placeholders rather than with
     * String.formatted().
     *
     * The template contains percent-encoded text of its own — the favicon data
     * URI — and a format string treats every % as a conversion, so formatting
     * would fail on the page's own markup. Straight replacement also means an
     * inlined stylesheet or a trip title can never be misread as a directive.
     */
    private String page(Trip trip, PublishedTrip snapshot, String payload, String theme) {
        String flags = snapshot.countries().stream()
                .map(PublishedTrip.Country::flag)
                .filter(flag -> flag != null && !flag.isBlank())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);

        String template = """
                <!doctype html>
                <html lang="en" data-theme="{{theme}}" data-themes="{{themes}}">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{title}}</title>
                <meta name="description" content="{{title}} — {{startDate}} to {{endDate}}">
                <meta property="og:title" content="{{ogTitle}}">
                <meta name="robots" content="index, follow">
                <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E%F0%9F%A6%99%3C/text%3E%3C/svg%3E">
                <style>
                {{css}}
                </style>
                </head>
                <body>
                <noscript>
                  <div class="noscript">This trip page needs JavaScript to render its itinerary.</div>
                </noscript>
                <div id="app" class="page"></div>
                <footer class="brand-footer">Planned with 🦙 <a href="{{siteUrl}}">Travelling Llama</a></footer>
                <script>window.TRIP = {{payload}};</script>
                <script>
                {{vendor}}
                </script>
                <script>
                {{js}}
                </script>
                </body>
                </html>
                """;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("theme", theme);
        // Which themes the reader may switch to. Ordered, comma-separated, read
        // by page.js. Inlined rather than hardcoded in page.js so THEMES stays
        // the single source of what is on offer — withdraw a theme and newly
        // published pages stop offering it, with no second list to remember.
        // A page published earlier keeps the list it shipped with, which is
        // inherent to a static file and harmless: every palette is inlined.
        values.put("themes", String.join(",", offeredThemes()));
        values.put("title", escapeHtml(trip.getTitle()));
        values.put("siteUrl", escapeHtml(siteUrl));
        values.put("ogTitle", escapeHtml(trip.getTitle() + " " + flags));
        values.put("startDate", snapshot.startDate() == null ? "" : snapshot.startDate());
        values.put("endDate", snapshot.endDate() == null ? "" : snapshot.endDate());
        values.put("css", resource("publish/page.css"));
        // Chart.js is inlined, not linked: the page has to draw its budget
        // chart with no network behind it. It costs ~200KB per published page
        // and buys a charting engine that handles the geometry — including the
        // small-slice and single-category cases a hand-drawn pie gets wrong.
        values.put("vendor", resource("publish/vendor/chart.umd.js"));
        values.put("js", resource("publish/page.js"));
        values.put("payload", payload);

        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            // Literal replacement: no regex escapes, no format conversions.
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    private String writeJson(PublishedTrip snapshot) {
        try {
            // The payload sits inside a <script> block, so a "</script>" in any
            // free-text field would end it early. Escaping the slash keeps the
            // JSON valid while making that impossible.
            return json.writeValueAsString(snapshot)
                    .replace("</", "<\\/")
                    .replace(" ", "\\u2028")
                    .replace(" ", "\\u2029");
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the published trip", e);
        }
    }

    private String resource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing published-page asset: " + path, e);
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String iso(LocalDate date) {
        return date == null ? null : ISO_DATE.format(date);
    }

    private static String time(LocalTime value) {
        return value == null ? null : TIME.format(value);
    }
}
