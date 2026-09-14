package com.josephinealinea.planner.rates;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.rates.domain.RateTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily rates from open.er-api.com, which is free and needs no key.
 *
 * <b>Why this provider and not an ECB feed.</b> The obvious free choices —
 * frankfurter, the ECB reference rates behind them — carry about thirty
 * currencies, all of them major. This app is for trips, and a trip's costs land
 * in PEN, BOB, VND: exactly the currencies those feeds omit. open.er-api.com
 * answers with 166, including every code in the catalogue.
 *
 * It updates once a day and says so in the response
 * ({@code time_next_update_utc}), which is what the default cron is set just
 * after. Asking more often than that returns the same numbers.
 *
 * The response carries every currency it knows; only the catalogue in
 * {@code app.currencies.available} is kept, so the stored file stays readable
 * and a currency nobody can select never appears in it.
 */
@Service
public class ExchangeRatesClient {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesClient.class);

    /** The subset of the provider's response this app reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(String result,
                    @JsonProperty("base_code") String baseCode,
                    @JsonProperty("time_last_update_utc") String lastUpdate,
                    Map<String, BigDecimal> rates) {

        boolean failed() {
            // The provider answers 200 with result:"error" for a bad base, so
            // the status code alone is not enough to tell success from failure.
            return !"success".equalsIgnoreCase(result) || rates == null || rates.isEmpty();
        }
    }

    private final RestClient client;
    private final String base;
    private final List<String> catalogue;

    // The parameter name is the bean name Spring injects by. Deliberately not
    // "exchangeRatesClient": that is this class's own bean name, and a @Bean
    // method cannot share it. GeocodingConfig sidesteps the same clash by
    // naming its client after the provider too.
    public ExchangeRatesClient(RestClient erApiClient, AppProperties props) {
        this.client = erApiClient;
        this.base = props.rates().base();
        this.catalogue = props.currencies().available();
    }

    /**
     * Fetches the current table, or null when the provider could not be read.
     *
     * Null rather than an exception or an empty table: the caller keeps
     * whatever it already had, so a failed refresh leaves yesterday's rates in
     * place instead of blanking every budget total on the install.
     */
    public RateTable fetch() {
        try {
            Response response = client.get()
                    .uri(uri -> uri.path("/v6/latest/{base}").build(base))
                    .retrieve()
                    .body(Response.class);

            if (response == null || response.failed()) {
                log.warn("Exchange rate provider returned no usable rates (result={})",
                        response == null ? "no body" : response.result());
                return null;
            }

            Map<String, BigDecimal> kept = new LinkedHashMap<>();
            for (String code : catalogue) {
                BigDecimal rate = response.rates().get(code);
                if (rate != null && rate.signum() > 0) kept.put(code, rate);
            }
            // The base itself is quoted as 1, which keeps the conversion in
            // BudgetService uniform: every currency is looked up the same way
            // and none is a special case.
            kept.putIfAbsent(base, BigDecimal.ONE);

            RateTable table = new RateTable();
            table.setBase(response.baseCode() == null ? base : response.baseCode());
            table.setDate(response.lastUpdate());
            table.setFetchedAt(Instant.now());
            table.setRates(kept);

            log.info("Exchange rates updated: {} currencies against {} ({})",
                    kept.size(), table.getBase(), table.getDate());
            return table;
        } catch (Exception e) {
            log.warn("Exchange rate lookup failed: {}", e.getMessage());
            return null;
        }
    }
}
