package com.josephinealinea.planner.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a two-letter country code into a name, a flag emoji and a currency.
 * There are only ~250 countries and the data does not change, so results are
 * cached for the life of the process — including negative lookups, so a bad
 * code is not re-requested on every keystroke.
 */
@Component
public class CountryCatalog {

    private static final Logger log = LoggerFactory.getLogger(CountryCatalog.class);

    private final RestClient client;
    private final Map<String, Optional<CountriesDevDtos.Country>> cache = new ConcurrentHashMap<>();
    // The whole list, fetched once. Held separately from the by-code cache
    // because a miss here is worth retrying — an outage on first use should
    // not leave the picker permanently empty for the life of the process.
    private volatile List<CountriesDevDtos.Country> all;

    public CountryCatalog(RestClient countriesDevClient) {
        this.client = countriesDevClient;
    }

    public Optional<CountriesDevDtos.Country> byCode(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) return Optional.empty();
        return cache.computeIfAbsent(countryCode.toUpperCase(), this::fetch);
    }

    public String nameOf(String countryCode) {
        return byCode(countryCode).map(CountriesDevDtos.Country::name).orElse(null);
    }

    public String flagOf(String countryCode) {
        return byCode(countryCode).map(CountriesDevDtos.Country::flag).orElse(null);
    }

    public String currencyOf(String countryCode) {
        return byCode(countryCode).map(CountriesDevDtos.Country::primaryCurrency).orElse(null);
    }

    /**
     * Every country, by name, for a picker to choose from.
     *
     * The whole point of offering this rather than a free-text code box is
     * that the code stops being something to get right by hand: the caller
     * picks "Peru" and the alpha-2 comes with it. Populating the by-code cache
     * on the way through means the flag and currency lookups that follow cost
     * nothing.
     */
    public List<CountriesDevDtos.Country> all() {
        List<CountriesDevDtos.Country> cached = all;
        if (cached != null) return cached;

        try {
            CountriesDevDtos.Country[] fetched = client.get()
                    .uri("/countries")
                    .retrieve()
                    .body(CountriesDevDtos.Country[].class);
            if (fetched == null) return List.of();

            List<CountriesDevDtos.Country> usable = java.util.Arrays.stream(fetched)
                    .filter(country -> country.alpha2Code() != null && country.name() != null)
                    .sorted(Comparator.comparing(CountriesDevDtos.Country::name))
                    .toList();
            usable.forEach(country ->
                    cache.putIfAbsent(country.alpha2Code().toUpperCase(), Optional.of(country)));
            all = usable;
            return usable;
        } catch (Exception e) {
            log.debug("Country list lookup failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<CountriesDevDtos.Country> fetch(String code) {
        try {
            return Optional.ofNullable(client.get()
                    .uri("/alpha/{code}", code)
                    .retrieve()
                    .body(CountriesDevDtos.Country.class));
        } catch (Exception e) {
            log.debug("Country lookup failed for {}: {}", code, e.getMessage());
            return Optional.empty();
        }
    }
}
