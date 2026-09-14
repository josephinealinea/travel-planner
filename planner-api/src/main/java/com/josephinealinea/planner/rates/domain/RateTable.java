package com.josephinealinea.planner.rates.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One day's exchange rates for every currency in the catalogue, quoted against
 * a single base.
 *
 * Global rather than per-trip. Rates are a property of the world on a given
 * day, not of anybody's holiday, and the previous per-trip table meant the same
 * number was typed in by hand once per trip and then went quietly out of date.
 *
 * <b>The base is the table's anchor and never changes.</b> Every rate reads as
 * "this many units per one unit of {@code base}", so a cross-rate between any
 * two currencies is a divide then a multiply through it. That is what lets a
 * trip's display currency be a pure presentation choice: it no longer anchors
 * anything, so changing it cannot invalidate a stored number. The old
 * per-trip table did anchor on the display currency, which is why
 * {@code TripService.rebase} had to exist — and why it is gone.
 */
public class RateTable {

    private String base;

    /** The provider's own date for these rates, not the day we fetched them. */
    private String date;

    /** When this app last successfully fetched. What staleness is measured on. */
    private Instant fetchedAt;

    private Map<String, BigDecimal> rates = new LinkedHashMap<>();

    public RateTable() {}

    /** An empty table reads as "no rates known", which converts nothing. */
    @JsonIgnore
    public boolean isEmpty() {
        return base == null || rates == null || rates.isEmpty();
    }

    /**
     * Named for the question rather than with a get/is prefix Jackson would
     * bind to a property — see ItineraryItem.coversWholeDay in CLAUDE.md.
     */
    @JsonIgnore
    public BigDecimal rateFor(String currency) {
        if (currency == null || rates == null) return null;
        return rates.get(currency.trim().toUpperCase());
    }

    public String getBase() { return base; }
    public void setBase(String base) { this.base = base; }

    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    public Map<String, BigDecimal> getRates() { return rates; }
    public void setRates(Map<String, BigDecimal> rates) {
        this.rates = rates == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rates);
    }
}
