package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
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

    /** Must match the theme names the frontend's theme registry offers. */
    private static final Set<String> THEMES = Set.of("minima", "retro-game", "y2k", "manila");
    private static final String DEFAULT_THEME = "minima";

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final BudgetService budgets;
    private final YamlStore store;
    private final YamlPaths paths;
    private final ObjectMapper json;

    public StaticSiteRenderer(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetService budgets,
                              YamlStore store,
                              YamlPaths paths) {
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budgets = budgets;
        this.store = store;
        this.paths = paths;
        this.json = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    public static String safeTheme(String theme) {
        return THEMES.contains(theme) ? theme : DEFAULT_THEME;
    }

    /** Writes index.html and trip.json into the trip's publish directory. */
    public void render(Trip trip) {
        PublishedTrip snapshot = snapshot(trip);
        String payload = writeJson(snapshot);

        var dir = paths.publishedTrip(trip.getSlug());
        store.writeText(dir.resolve("index.html"),
                page(trip, snapshot, payload, safeTheme(trip.getPublishedTheme())));
        store.writeText(dir.resolve("trip.json"), payload);

        log.info("Published \"{}\" to {}", trip.getTitle(), dir);
    }

    public void remove(String slug) {
        store.deleteTree(paths.publishedTrip(slug));
    }

    // ── snapshot ────────────────────────────────────────────────────────────

    private PublishedTrip snapshot(Trip trip) {
        String slug = trip.getSlug();
        var allDestinations = destinations.findAllOrdered(slug);
        var allChecklist = checklist.findAllOrdered(slug);
        var allItinerary = itinerary.findAllOrdered(slug);
        BudgetService.Summary budget = budgets.summarise(trip);

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

        Map<String, String> destinationNames = new LinkedHashMap<>();
        allDestinations.forEach(d -> destinationNames.put(d.getId(), d.getName()));

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
                allDestinations.stream().map(this::toView).toList(),
                days(allItinerary),
                allChecklist.stream()
                        .map(item -> toView(item, destinationNames))
                        .toList(),
                toView(budget, trip.getExchangeRates()));
    }

    private PublishedTrip.Destination toView(
            com.josephinealinea.planner.destinations.domain.Destination destination) {
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
                destination.nights(),
                destination.getNotes(),
                mapUrl);
    }

    private PublishedTrip.Checklist toView(ChecklistItem item, Map<String, String> destinationNames) {
        return new PublishedTrip.Checklist(
                item.getCategory().dataKey(),
                item.getCategory().label(),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                item.getNote(),
                item.isCompleted() ? "done" : "todo",
                item.isCompleted() ? PublishStyle.DONE_ICON : PublishStyle.TODO_ICON,
                item.getDestinationId() == null ? null : destinationNames.get(item.getDestinationId()));
    }

    /** Groups itinerary entries by day; undated plans are left out of the timeline. */
    private List<PublishedTrip.Day> days(List<ItineraryItem> items) {
        Map<LocalDate, List<PublishedTrip.Entry>> byDay = new LinkedHashMap<>();

        items.stream()
                .filter(item -> item.getStartAt() != null)
                .sorted(Comparator.comparing(ItineraryItem::getStartAt))
                .forEach(item -> byDay
                        .computeIfAbsent(item.getStartAt().toLocalDate(), key -> new ArrayList<>())
                        .add(new PublishedTrip.Entry(
                                item.getCategory().dataKey(),
                                item.getCategory().label(),
                                PublishStyle.icon(item.getCategory()),
                                item.getDescription(),
                                time(item.getStartAt() == null ? null : item.getStartAt().toLocalTime()),
                                time(item.getEndAt() == null ? null : item.getEndAt().toLocalTime()),
                                item.getCost() == null ? null : item.getCost().toPlainString(),
                                item.getCurrency())));

        return byDay.entrySet().stream()
                .map(entry -> new PublishedTrip.Day(iso(entry.getKey()), entry.getValue()))
                .toList();
    }

    private PublishedTrip.Budget toView(BudgetService.Summary summary, Map<String, BigDecimal> rates) {
        List<PublishedTrip.Budget.Category> categories = new ArrayList<>();
        summary.byCategory().forEach((key, amount) -> {
            if (amount.signum() == 0) return;
            ChecklistCategory category = ChecklistCategory.valueOf(key);
            categories.add(new PublishedTrip.Budget.Category(
                    category.dataKey(),
                    category.label(),
                    PublishStyle.icon(category),
                    PublishStyle.color(category),
                    amount));
        });

        List<PublishedTrip.Budget.Line> lines = summary.items().stream()
                .map(item -> new PublishedTrip.Budget.Line(
                        item.getDescription(),
                        item.getCategory().label(),
                        PublishStyle.icon(item.getCategory()),
                        item.getAmount(),
                        item.getCurrency(),
                        BudgetService.convert(item, summary.displayCurrency(), rates),
                        iso(item.getDate())))
                .toList();

        return new PublishedTrip.Budget(
                summary.displayCurrency(),
                summary.total(),
                categories,
                lines,
                summary.currenciesMissingRates());
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
                <html lang="en" data-theme="{{theme}}">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{title}}</title>
                <meta name="description" content="{{title}} — {{startDate}} to {{endDate}}">
                <meta property="og:title" content="{{ogTitle}}">
                <meta name="robots" content="index, follow">
                <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E%F0%9F%A7%AD%3C/text%3E%3C/svg%3E">
                <style>
                {{css}}
                </style>
                </head>
                <body>
                <noscript>
                  <div class="noscript">This trip page needs JavaScript to render its itinerary.</div>
                </noscript>
                <div id="app" class="page"></div>
                <script>window.TRIP = {{payload}};</script>
                <script>
                {{js}}
                </script>
                </body>
                </html>
                """;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("theme", theme);
        values.put("title", escapeHtml(trip.getTitle()));
        values.put("ogTitle", escapeHtml(trip.getTitle() + " " + flags));
        values.put("startDate", snapshot.startDate() == null ? "" : snapshot.startDate());
        values.put("endDate", snapshot.endDate() == null ? "" : snapshot.endDate());
        values.put("css", resource("publish/page.css"));
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
