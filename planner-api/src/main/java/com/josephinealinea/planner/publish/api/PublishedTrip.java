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
                              Long nights,
                              String notes,
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

    public record Budget(String displayCurrency,
                         BigDecimal total,
                         List<Category> byCategory,
                         List<Country> byCountry,
                         List<Native> nativeTotals,
                         List<String> currenciesMissingRates) {

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
