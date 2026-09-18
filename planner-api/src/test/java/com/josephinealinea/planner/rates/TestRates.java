package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.rates.infra.YamlRatesRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A RatesService for tests, pre-loaded and unable to reach the network.
 *
 * Rates used to be typed into each trip, so a test that needed a conversion
 * set them on the Trip. They are now one table for the install, so a test needs
 * one of these instead. The client is pointed at nothing on purpose: a fetch
 * — refresh(), or current() finding the table stale, which for empty() it
 * always does — fails fast rather than quietly hitting a live provider, and a
 * failed fetch keeps whatever table is held.
 */
public final class TestRates {

    private TestRates() {}

    private static final Instant FETCHED_AT = Instant.parse("2026-09-14T01:30:00Z");

    /**
     * Pinned to the moment the table was fetched, so the table never reads as
     * stale and {@link RatesService#current()} never tries to refresh it —
     * which, with the client pointed at nothing, would only fail and log.
     */
    private static final Clock FRESH = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);

    /** Rates quoted as "units per one EUR", the same shape the provider sends. */
    public static RatesService with(YamlStore store, YamlPaths paths, AppProperties props,
                                    Map<String, BigDecimal> perEuro) {
        RateTable table = new RateTable();
        table.setBase("EUR");
        table.setDate("Mon, 14 Sep 2026 00:02:31 +0000");
        table.setFetchedAt(FETCHED_AT);

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("EUR", BigDecimal.ONE);
        if (perEuro != null) rates.putAll(perEuro);
        table.setRates(rates);

        RatesRepository repository = new YamlRatesRepository(store, paths);
        repository.save(table);
        return new RatesService(new ExchangeRatesClient(RestClient.create(), props), repository, FRESH);
    }

    /** No rates known at all — what a fresh install looks like. */
    public static RatesService empty(YamlStore store, YamlPaths paths, AppProperties props) {
        RatesRepository repository = new YamlRatesRepository(store, paths);
        return new RatesService(new ExchangeRatesClient(RestClient.create(), props), repository);
    }
}
