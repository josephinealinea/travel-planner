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

    /** Itinerary grouped by calendar day, which is how the page reads. */
    public record Day(String date, List<Entry> entries) {}

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
                            String destination) {}

    public record Budget(String displayCurrency,
                         BigDecimal total,
                         List<Category> byCategory,
                         List<Line> items,
                         List<String> currenciesMissingRates) {

        public record Category(String key, String label, String icon, String color, BigDecimal amount) {}

        public record Line(String description,
                           String categoryLabel,
                           String icon,
                           BigDecimal amount,
                           String currency,
                           BigDecimal converted,
                           String date) {}
    }
}
