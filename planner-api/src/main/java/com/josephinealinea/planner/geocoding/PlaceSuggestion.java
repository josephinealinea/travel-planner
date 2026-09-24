package com.josephinealinea.planner.geocoding;

/** One autocomplete row, already enriched with the country's flag and currency. */
public record PlaceSuggestion(
        Long geonameId,
        String name,
        String countryCode,
        String countryFlag,
        String currency,
        Double latitude,
        Double longitude,
        Long population,
        String timezone) {}
