package com.josephinealinea.planner.geocoding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The subset of countries.dev's responses this app reads. */
public final class CountriesDevDtos {

    private CountriesDevDtos() {}

    /**
     * Shared by /cities and /places. featureClass is only present on /places:
     * "P" is a populated place, "A" an administrative region, "S" a building
     * such as a hotel.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            Long geonameId,
            String name,
            String asciiName,
            String featureClass,
            String featureCode,
            String countryCode,
            String admin1Code,
            Double latitude,
            Double longitude,
            Long population,
            String timezone) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Country(
            String name,
            String flag,
            String alpha2Code,
            List<Currency> currencies) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Currency(String code, String name, String symbol) {}

        public String primaryCurrency() {
            return currencies == null || currencies.isEmpty() ? null : currencies.get(0).code();
        }
    }
}
