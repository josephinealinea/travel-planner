package com.josephinealinea.planner.rates.infra;

import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.trips.infra.EveryField;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every behaviour {@link YamlRatesRepository} has, as assertions any
 * {@link RatesRepository} must pass. Run against a temp directory by
 * {@code YamlRatesRepositoryContractTest} and against PostgreSQL by
 * {@code JdbcRatesRepositoryContractTest}.
 */
public abstract class RatesRepositoryContract {

    protected abstract RatesRepository repository();

    /** A fresh install: what the YAML version answers when rates.yml does not exist. */
    @Test
    void nothingSavedLoadsAsAnEmptyTableNeverNull() {
        RateTable table = repository().load();

        assertThat(table).isNotNull();
        assertThat(table.isEmpty()).isTrue();
        assertThat(table.getBase()).isNull();
        assertThat(table.getDate()).isNull();
        assertThat(table.getFetchedAt()).isNull();
        assertThat(table.getRates()).isEmpty();
    }

    @Test
    void everyFieldSurvivesARoundTrip() {
        RateTable table = table(List.of("EUR", "USD", "PEN", "BOB"));
        EveryField.assertEverySet(table, RateTable::new, Set.of());

        repository().save(table);

        assertThat(repository().load())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(table);
    }

    /** The catalogue order the client wrote is the order that comes back. */
    @Test
    void theRatesKeepTheirOrder() {
        List<String> order = List.of("VND", "EUR", "SGD", "BOB", "USD", "PEN", "ARS", "CLP", "GBP", "AUD", "JPY");

        repository().save(table(order));

        assertThat(repository().load().getRates().keySet()).containsExactlyElementsOf(order);
    }

    @Test
    void ratesKeepTheirPrecision() {
        repository().save(table(List.of("EUR", "VND")));

        RateTable read = repository().load();
        assertThat(read.rateFor("VND")).isEqualByComparingTo("30512.123456");
        assertThat(read.rateFor("EUR")).isEqualByComparingTo("1");
    }

    /** Saving replaces the whole table, as rewriting the file does. */
    @Test
    void savingReplacesThePreviousTableEntirely() {
        repository().save(table(List.of("EUR", "USD", "PEN", "BOB")));

        RateTable tomorrow = table(List.of("EUR", "SGD"));
        tomorrow.setDate("Tue, 15 Sep 2026 00:02:31 +0000");
        tomorrow.setFetchedAt(Instant.parse("2026-09-15T01:30:00Z"));
        repository().save(tomorrow);

        RateTable read = repository().load();
        assertThat(read.getRates().keySet()).containsExactly("EUR", "SGD");
        assertThat(read.getDate()).isEqualTo("Tue, 15 Sep 2026 00:02:31 +0000");
        assertThat(read.getFetchedAt()).isEqualTo(Instant.parse("2026-09-15T01:30:00Z"));
    }

    // ── fixtures ─────

    /** Distinct, non-trivial rates in the given key order. */
    protected static RateTable table(List<String> currencies) {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (int i = 0; i < currencies.size(); i++) {
            String code = currencies.get(i);
            rates.put(code, switch (code) {
                case "EUR" -> BigDecimal.ONE;
                case "VND" -> new BigDecimal("30512.123456");
                default -> new BigDecimal("1.1597" + i);
            });
        }
        RateTable table = new RateTable();
        table.setBase("EUR");
        table.setDate("Mon, 14 Sep 2026 00:02:31 +0000");
        table.setFetchedAt(Instant.parse("2026-09-14T01:30:12.345678Z"));
        table.setRates(rates);
        return table;
    }
}
