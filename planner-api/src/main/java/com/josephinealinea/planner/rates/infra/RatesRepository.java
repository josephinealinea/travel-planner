package com.josephinealinea.planner.rates.infra;

import com.josephinealinea.planner.rates.domain.RateTable;

/**
 * The storage contract for the install's one exchange-rate table:
 * {@link YamlRatesRepository} with the database flag off, a JDBC
 * implementation with it on.
 *
 * Not trip-scoped — rates are a property of the day, not of a trip — so this
 * is two calls rather than a TripScopedRepository.
 */
public interface RatesRepository {

    /** An empty table when nothing has been fetched yet — never null. */
    RateTable load();

    void save(RateTable table);
}
