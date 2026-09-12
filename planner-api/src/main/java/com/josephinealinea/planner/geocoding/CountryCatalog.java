package com.josephinealinea.planner.geocoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
