package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A RatesService for tests, pre-loaded and unable to reach the network.
 *
 * Rates used to be typed into each trip, so a test that needed a conversion
 * set them on the Trip. They are now one table for the install, so a test needs
 * one of these instead. The client is pointed at nothing on purpose: only
 * refresh() would use it, no test calls that, and a test that accidentally did
 * would fail fast rather than quietly hitting a live provider.
 */
public final class TestRates {

    private TestRates() {}

    /** Rates quoted as "units per one EUR", the same shape the provider sends. */
    public static RatesService with(YamlStore store, YamlPaths paths, AppProperties props,
                                    Map<String, BigDecimal> perEuro) {
        RateTable table = new RateTable();
        table.setBase("EUR");
        table.setDate("Mon, 14 Sep 2026 00:02:31 +0000");
        table.setFetchedAt(Instant.parse("2026-09-14T01:30:00Z"));

        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("EUR", BigDecimal.ONE);
        if (perEuro != null) rates.putAll(perEuro);
        table.setRates(rates);

        RatesRepository repository = new RatesRepository(store, paths);
        repository.save(table);
        return new RatesService(new ExchangeRatesClient(RestClient.create(), props), repository);
    }

    /** No rates known at all — what a fresh install looks like. */
    public static RatesService empty(YamlStore store, YamlPaths paths, AppProperties props) {
        RatesRepository repository = new RatesRepository(store, paths);
        return new RatesService(new ExchangeRatesClient(RestClient.create(), props), repository);
    }
}
