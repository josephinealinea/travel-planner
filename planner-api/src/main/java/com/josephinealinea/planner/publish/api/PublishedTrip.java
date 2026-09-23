package com.josephinealinea.planner.publish.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The data the published page renders from, inlined into it as JSON. It is a
 * deliberately flat, presentation-shaped snapshot rather than the domain model:
 * once a page is on a CDN it has no API to call, so everything it needs — the
 * nights count, the converted amounts, the country flags — is resolved here.
 */
public record PublishedTrip(
        String title,
        String slug,
        String startDate,
        String endDate,
        String publishedAt,
        List<Country> countries,
        String routeSummary,
        List<Destination> destinations,
        List<Day> days,
        List<Checklist> checklist,
        Budget budget) {

    public record Country(String name, String flag) {}

    public record Destination(String name,
                              String country,
                              String flag,
                              Double latitude,
                              Double longitude,
                              String startDate,
                              String endDate,
                              /**
                               * Exactly one of these is ever set — whichever
                               * the publishing account asked for. Shipping only
                               * the one that is shown keeps page.js free of a
                               * flag to interpret, and matches the rule that a
                               * setting which is off puts nothing in the
                               * payload.
                               */
                              Long nights,
                              Long days,
                              String note,
                              String mapUrl) {}

    /**
     * Itinerary grouped by calendar day, which is how the page reads, plus
     * where the trip is that day.
     *
     * `places` is what the published page hangs its weather off. It carries
     * coordinates rather than a reading: a published page has no API behind it
     * and may be read months after it was rendered, so baking a forecast in
     * would ship something guaranteed to be wrong. The page looks the weather
     * up in the reader's own browser instead — see page.js.
     */
    public record Day(String date, List<Entry> entries, List<Place> places) {}

    /** One place the trip is on a given day, with what a lookup needs. */
    public record Place(String name,
                        String country,
                        String flag,
                        Double latitude,
                        Double longitude) {}

    public record Entry(String category,
                        String categoryLabel,
                        String icon,
                        String description,
                        String startTime,
                        String endTime,
                        String cost,
                        String currency) {}

    public record Checklist(String category,
                            String categoryLabel,
                            String icon,
                            String description,
                            String note,
                            String status,
                            String statusIcon,
                            List<String> countries) {}

    /**
     * `charged` is what the trip has actually cost; `forecast` counts the
     * expenses still to be paid alongside it, and is <b>null unless the
     * publishing account asked for it</b> — the page carries no such figure at
     * all rather than shipping one it then hides, the same way a destination's
     * unused nights/days half is left null. page.js offers its two extra Group
     * by buttons only when this is present.
     */
    public record Budget(String displayCurrency,
                         Breakdown charged,
                         Breakdown forecast) {

        /** One rollup over one set of rows. See BudgetService.Breakdown. */
        public record Breakdown(BigDecimal total,
                                List<Category> byCategory,
                                List<Country> byCountry,
                                List<Native> nativeTotals,
                                List<String> currenciesMissingRates) {}

        public record Category(String key, String label, String icon, String color, BigDecimal amount) {}

        /**
         * A country slice. No icon or colour from PublishStyle here — countries
         * are not a fixed set the way the four categories are, so the page
         * colours them itself and uses the flag it was given.
         */
        public record Country(String key, String label, String flag, BigDecimal amount) {}

        /** How much was actually spent in one currency, before conversion. */
        public record Native(String currency, BigDecimal amount) {}
    }
}
