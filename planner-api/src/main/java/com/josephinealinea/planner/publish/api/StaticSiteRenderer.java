package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.i18n.Messages;
import java.util.Locale;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.geocoding.EmergencyNumbers;
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
    private final CountryCatalog catalog;
    private final PageStore pages;
    private final ObjectMapper json;
    /** Where the "Planned with Travelling Llama" line links: the website, not the API. */
    private final String siteUrl;
    private final Messages messages;

    /** Without a catalog no country details are looked up; the panel shows what it has. */
    public StaticSiteRenderer(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetService budgets,
                              PageStore pages,
                              AppProperties props) {
        this(destinations, checklist, itinerary, budgets, pages, props, null);
    }

    /** English message files, for tests that build the renderer by hand. */
    public StaticSiteRenderer(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetService budgets,
                              PageStore pages,
                              AppProperties props,
                              CountryCatalog catalog) {
        this(destinations, checklist, itinerary, budgets, pages, props, catalog,
                I18nConfig.standalone());
    }

    @Autowired
    public StaticSiteRenderer(DestinationRepository destinations,
                              ChecklistRepository checklist,
                              ItineraryRepository itinerary,
                              BudgetService budgets,
                              PageStore pages,
                              AppProperties props,
                              CountryCatalog catalog,
                              Messages messages) {
        this.messages = messages;
        this.catalog = catalog;
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
    public void render(Trip trip, PublishOptions options, String theme) {
        render(trip, options, theme, List.of());
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
    public void render(Trip trip, PublishOptions options, String requestedTheme,
                       List<PersonalPage> personal) {
        var theme = safeTheme(requestedTheme);
        write(trip, publicOptions(options, personal), theme, Area.PUBLISHED, null, null);
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
        write(trip, publicOptions(options, personal), safeTheme(theme), Area.PENDING, null, null);
        writePersonal(trip, safeTheme(theme), Area.PENDING, personal);
        log.info("Staged \"{}\" for approval", trip.getTitle());
    }

    /**
     * What the trip's own, public page may show: the publisher's settings for
     * the itinerary and destinations, except that
     * <ul>
     *   <li>it never has a Budget section — what a trip costs is for a
     *       member's own page, where it is their share, not the public's;</li>
     *   <li>its home countries are everybody's who asked for one, so the page
     *       does not present the publisher's home as the trip's.</li>
     * </ul>
     */
    private static PublishOptions publicOptions(PublishOptions options, List<PersonalPage> personal) {
        List<String> homes = personal.stream()
                .flatMap(page -> page.options().homeCountryCodes().stream())
                .map(code -> code.trim().toUpperCase())
                .distinct().toList();
        return new PublishOptions(options.itineraryCost(), options.destinationDays(),
                options.forecastExpenses(), false, homes, options.languageCode());
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
        // Whose page this is decides its language, like everything else about
        // it: a static file cannot ask its reader, and the request that
        // triggered the publish belongs to somebody else.
        Locale locale = messages.localeOrDefault(options.languageCode());
        PublishedTrip snapshot = snapshot(trip, options, viewer, locale);
        String payload = writeJson(snapshot);
        pages.write(area, trip.getSlug(), memberSlug, PageFile.INDEX,
                page(trip, snapshot, payload, theme, locale));
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
                                   com.josephinealinea.planner.identity.domain.User viewer,
                                   Locale locale) {
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

        // Countries in route order, de-duplicated: the flag strip and the
        // Destinations panel, with the nights (or days) summed per country.
        Map<String, List<Destination>> byCountry = new LinkedHashMap<>();
        allDestinations.forEach(destination -> {
            String code = destination.getCountryCode();
            if (code != null && !code.isBlank()) {
                byCountry.computeIfAbsent(code.toUpperCase(), k -> new ArrayList<>()).add(destination);
            }
        });
        List<PublishedTrip.Country> countries = byCountry.entrySet().stream()
                .map(e -> toCountry(e.getKey(), e.getValue(), options))
                .toList();

        // "Tallinn -> Los Angeles -> Cusco", collapsing repeats.
        List<String> route = new ArrayList<>(new LinkedHashSet<>(
                allDestinations.stream().map(d -> d.getName()).toList()));

        return new PublishedTrip(
                trip.getTitle(),
                slug,
                iso(trip.getStartDate()),
                iso(trip.getEndDate()),
                trip.getPublishedAt() == null ? null : trip.getPublishedAt().toString(),
                countries,
                options.homeCountryCodes().stream().map(code -> home(code, options)).toList(),
                String.join(" → ", route),
                allDestinations.stream().map(d -> toView(d, options)).toList(),
                days(allItinerary, allDestinations, options.itineraryCost(),
                        trip.getStartDate(), trip.getEndDate(), locale),
                allChecklist.stream()
                        .map(item -> toView(item, locale))
                        .toList(),
                options.displayBudget() ? toView(budget, options, locale) : null);
    }

    /**
     * The home country as a Destinations card with no stops, or null when the
     * account did not ask for it. Built through toCountry so it carries the
     * same facts as any other country card.
     */
    private PublishedTrip.Country home(String code, PublishOptions options) {
        if (code == null || code.isBlank()) return null;
        String upper = code.trim().toUpperCase();
        PublishedTrip.Country card = toCountry(upper, List.of(), options);
        if (!card.flag().isEmpty() || upper.length() != 2) return card;
        // No stop to borrow a flag from, so spell it from the code: two
        // regional-indicator letters.
        String flag = upper.chars()
                .mapToObj(c -> new String(Character.toChars(0x1F1E6 + (c - 'A'))))
                .collect(java.util.stream.Collectors.joining());
        return new PublishedTrip.Country(card.code(), flag, card.nights(), card.days(), card.region(),
                card.capital(), card.languages(), card.demonym(), card.currencies(), card.callingCode(),
                card.emergencyNumber());
    }

    /**
     * How many different dates the stops cover: [start, end) for nights, so a
     * night is the date it starts on, or [start, end] for days. Stops with a
     * missing or backwards date count nothing, matching Destination.nights()
     * and days().
     */
    private static long distinctDates(List<Destination> stops, boolean inclusiveEnd) {
        Set<LocalDate> dates = new java.util.HashSet<>();
        for (Destination stop : stops) {
            LocalDate start = stop.getStartDate();
            LocalDate end = stop.getEndDate();
            if (start == null || end == null || end.isBefore(start)) continue;
            LocalDate last = inclusiveEnd ? end.plusDays(1) : end;
            for (LocalDate d = start; d.isBefore(last); d = d.plusDays(1)) dates.add(d);
        }
        return dates.size();
    }

    private PublishedTrip.Country toCountry(String code, List<Destination> stops,
                                            PublishOptions options) {
        String flag = stops.stream().map(Destination::getCountryFlag)
                .filter(f -> f != null && !f.isBlank()).findFirst().orElse("");
        // Distinct calendar nights (and days) across the stops, not a sum of
        // their counts: a stop can sit inside another one (Ollantaytambo
        // within a Cusco stay) and the shared night is still one night.
        long nights = distinctDates(stops, false);
        long days = distinctDates(stops, true);
        var details = catalog == null ? null : catalog.byCode(code).orElse(null);
        String region = null, capital = null, calling = null, demonym = null;
        List<String> languages = null, currencies = null;
        if (details != null) {
            region = details.subregion() == null || details.subregion().isBlank()
                    ? details.region()
                    : (details.region() == null ? "" : details.region() + " · ") + details.subregion();
            capital = details.capital();
            demonym = details.demonym();
            if (details.languages() != null) {
                languages = details.languages().stream()
                        .map(l -> l.name()).filter(n -> n != null).toList();
            }
            if (details.currencies() != null) {
                currencies = details.currencies().stream()
                        .filter(c -> c.code() != null)
                        .map(c -> c.symbol() == null || c.symbol().isBlank()
                                || c.symbol().equals(c.code())
                                ? c.code() : c.code() + " (" + c.symbol() + ")")
                        .toList();
            }
            if (details.callingCodes() != null && !details.callingCodes().isEmpty()) {
                calling = "+" + String.join(", +", details.callingCodes());
            }
        }
        return new PublishedTrip.Country(code, flag,
                options.destinationDays() || nights == 0 ? null : nights,
                options.destinationDays() && days > 0 ? days : null,
                region, capital, languages, demonym, currencies, calling, EmergencyNumbers.of(code));
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
                destination.getCountryCode(),
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
                mapUrl,
                destination.getTimezone());
    }

    /** Items carry country codes; the page turns them into names (see page.js). */
    private PublishedTrip.Checklist toView(ChecklistItem item, Locale locale) {
        List<String> codes = List.copyOf(item.getCountryCodes());
        return new PublishedTrip.Checklist(
                item.getCategory().dataKey(),
                messages.get(locale, item.getCategory().messageKey()),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                item.getNote(),
                item.isCompleted() ? "done" : "todo",
                item.isCompleted() ? PublishStyle.DONE_ICON : PublishStyle.TODO_ICON,
                codes);
    }

    /** Groups itinerary entries by day; undated plans and days outside the trip are left out of the timeline. */
    private List<PublishedTrip.Day> days(List<ItineraryItem> items,
                                        List<Destination> allDestinations,
                                        boolean showItineraryCost,
                                        LocalDate tripStart, LocalDate tripEnd,
                                        Locale locale) {
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
                        .add(entryFor(item, showItineraryCost, locale)));

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
                    destination.getCountryCode(),
                    destination.getCountryFlag(),
                    destination.getLatitude(),
                    destination.getLongitude());
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                placesByDay.computeIfAbsent(day, key -> new ArrayList<>()).add(place);
            }
        }

        List<LocalDate> allDays = new ArrayList<>(new TreeSet<>(
                Stream.concat(byDay.keySet().stream(), placesByDay.keySet().stream())
                        // A day outside the trip's own dates is kept in the data
                        // but never put on the page.
                        .filter(day -> (tripStart == null || !day.isBefore(tripStart))
                                && (tripEnd == null || !day.isAfter(tripEnd)))
                        .toList()));

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
    private PublishedTrip.Entry entryFor(ItineraryItem item, boolean showItineraryCost, Locale locale) {
        LocalTime shown = item.coversWholeDay() ? null : item.getStartAt().toLocalTime();
        LocalTime until = item.coversWholeDay() || item.getEndAt() == null
                ? null
                : item.getEndAt().toLocalTime();

        return new PublishedTrip.Entry(
                item.getCategory().dataKey(),
                messages.get(locale, item.getCategory().messageKey()),
                PublishStyle.icon(item.getCategory()),
                item.getDescription(),
                time(shown),
                time(until),
                // Off by default, and omitted rather than hidden — see render().
                showItineraryCost && item.getCost() != null
                        ? item.getCost().toPlainString() : null,
                showItineraryCost ? item.getCurrency() : null);
    }

    private PublishedTrip.Budget toView(BudgetService.Summary summary, PublishOptions options, Locale locale) {
        return new PublishedTrip.Budget(
                // The currency the figures below are actually in, which is not
                // the trip's own anchor. The two coincide on the trip's page
                // and come apart on a personal one: a member's totals are
                // converted into whatever display currency they keep, so
                // labelling them with the trip's anchor would put "EUR" under
                // a column of SGD.
                summary.totalsCurrency(),
                toView(summary.charged(), locale),
                // Off by default, and left null rather than shipped and then
                // hidden — see render().
                options.forecastExpenses() ? toView(summary.forecast(), locale) : null);
    }

    private PublishedTrip.Budget.Breakdown toView(BudgetService.Breakdown breakdown, Locale locale) {
        List<PublishedTrip.Budget.Category> categories = new ArrayList<>();
        breakdown.byCategory().forEach((key, amount) -> {
            if (amount.signum() == 0) return;
            ChecklistCategory category = ChecklistCategory.valueOf(key);
            categories.add(new PublishedTrip.Budget.Category(
                    category.dataKey(),
                    messages.get(locale, category.messageKey()),
                    PublishStyle.icon(category),
                    PublishStyle.color(category),
                    amount));
        });

        List<PublishedTrip.Budget.Country> countries = breakdown.byCountry().stream()
                .filter(country -> country.amount() != null && country.amount().signum() != 0)
                .map(country -> new PublishedTrip.Budget.Country(
                        country.key(), country.flag(), country.amount()))
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
    private String page(Trip trip, PublishedTrip snapshot, String payload, String theme, Locale locale) {
        String flags = snapshot.countries().stream()
                .map(PublishedTrip.Country::flag)
                .filter(flag -> flag != null && !flag.isBlank())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);

        String template = """
                <!doctype html>
                <html lang="{{lang}}" data-theme="{{theme}}" data-themes="{{themes}}">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>{{title}}</title>
                <meta name="description" content="{{description}}">
                <meta property="og:title" content="{{ogTitle}}">
                <meta name="robots" content="index, follow">
                <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'%3E%3Ctext y='.9em' font-size='90'%3E%F0%9F%A6%99%3C/text%3E%3C/svg%3E">
                <style>
                {{css}}
                </style>
                </head>
                <body>
                <noscript>
                  <div class="noscript">{{noscript}}</div>
                </noscript>
                <main id="app" class="page"></main>
                <footer class="brand-footer">{{footerPrefix}} 🦙 <a href="{{siteUrl}}">Travelling Llama</a></footer>
                <script>window.COUNTRIES = {{countries}};</script>
                <script>window.I18N = {{i18n}};</script>
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
        values.put("lang", locale.getLanguage());
        values.put("noscript", escapeHtml(messages.get(locale, "page.noscript")));
        values.put("footerPrefix", escapeHtml(messages.get(locale, "page.footer.prefix")));
        values.put("description", escapeHtml(fill(messages.get(locale, "page.meta.description"), Map.of(
                "title", trip.getTitle(),
                "start", snapshot.startDate() == null ? "" : snapshot.startDate(),
                "end", snapshot.endDate() == null ? "" : snapshot.endDate()))));
        values.put("i18n", i18nJson(locale));
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
        // Code -> name, the same table the planner uses (planner-web/js/countries.js).
        // Public reference data, so shipping all of it leaks nothing about the trip.
        values.put("countries", resource("publish/countries.json").trim());
        values.put("payload", payload);

        String out = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            // Literal replacement: no regex escapes, no format conversions.
            out = out.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return out;
    }

    /**
     * The words the page's script looks up: only {@code page.*} — a public file
     * gets what it needs to draw itself, not the API's error messages or emails —
     * with English filling anything the language lacks. Sits inside a
     * &lt;script&gt; block, so "&lt;/" is escaped the way the payload's is.
     */
    private String i18nJson(Locale locale) {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("_lang", locale.getLanguage());
        table.putAll(messages.withPrefix(locale, "page."));
        try {
            return json.writeValueAsString(table).replace("</", "<\\/");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the page's words", e);
        }
    }

    /** Single-pass {name} substitution, so a value that itself contains "{start}" is left alone. */
    private static String fill(String template, Map<String, String> values) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)}").matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
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
