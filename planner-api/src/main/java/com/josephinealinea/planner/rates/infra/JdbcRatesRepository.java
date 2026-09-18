package com.josephinealinea.planner.rates.infra;

import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The install's one exchange-rate table in PostgreSQL: {@code rate_table}, a
 * single row holding the base, the provider's date and when it was fetched,
 * and {@code exchange_rates}, one row per currency.
 *
 * Behaves as {@link YamlRatesRepository} does with {@code rates.yml}:
 *
 * <ul>
 *   <li><b>{@link #load} on an empty database is a {@code new RateTable()}</b>
 *       — no base, no date, no rates — exactly what the YAML version answers
 *       when the file does not exist. Callers read that as "nothing fetched
 *       yet" and never see null.</li>
 *   <li><b>{@link #save} replaces the whole table</b>, as rewriting the file
 *       does, in one transaction so a reader never sees Monday's base with
 *       half of Tuesday's rates.</li>
 *   <li><b>The map keeps its order.</b> {@code position} is the map's
 *       iteration order, so the catalogue order the client wrote is the order
 *       that comes back, as it does from a YAML mapping.</li>
 * </ul>
 *
 * {@code rate_table.base} is {@code NOT NULL}, so a table with no base — which
 * the app never saves, and which YAML would store as a file with no
 * {@code base:} key — stores its rates (if any) and no row; loading it gives
 * the same null base back, though unlike YAML also a null date and fetchedAt.
 * A table without a base is an empty table either way ({@code isEmpty()}).
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcRatesRepository implements RatesRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcRatesRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public RateTable load() {
        return transactions.execute(status -> {
            RateTable table = jdbc.sql("SELECT base, date, fetched_at FROM rate_table")
                    .query((rs, n) -> {
                        RateTable row = new RateTable();
                        row.setBase(rs.getString("base"));
                        row.setDate(rs.getString("date"));
                        row.setFetchedAt(JdbcValues.instant(rs, "fetched_at"));
                        return row;
                    })
                    .optional()
                    .orElseGet(RateTable::new);

            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            jdbc.sql("SELECT currency, rate FROM exchange_rates ORDER BY position")
                    .query(rs -> {
                        rates.put(rs.getString("currency"), rs.getBigDecimal("rate"));
                    });
            table.setRates(rates);
            return table;
        });
    }

    @Override
    public void save(RateTable table) {
        transactions.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM exchange_rates").update();
            jdbc.sql("DELETE FROM rate_table").update();

            if (table.getBase() != null) {
                jdbc.sql("INSERT INTO rate_table (base, date, fetched_at) VALUES (:base, :date, :fetchedAt)")
                        .param("base", table.getBase())
                        .param("date", table.getDate())
                        .param("fetchedAt", JdbcValues.timestamptz(table.getFetchedAt()))
                        .update();
            }

            int position = 0;
            for (Map.Entry<String, BigDecimal> rate : table.getRates().entrySet()) {
                jdbc.sql("INSERT INTO exchange_rates (currency, position, rate) VALUES (:currency, :position, :rate)")
                        .param("currency", rate.getKey())
                        .param("position", position++)
                        .param("rate", rate.getValue())
                        .update();
            }
        });
    }
}
