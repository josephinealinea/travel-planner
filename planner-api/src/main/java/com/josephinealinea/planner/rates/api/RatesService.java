package com.josephinealinea.planner.rates.api;

import com.josephinealinea.planner.rates.ExchangeRatesClient;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The install's one source of exchange rates.
 *
 * Reads are served from memory so a budget rollup never touches the disk; the
 * file is the copy that survives a restart, and is loaded once on first use.
 *
 * <b>A failed refresh never clears what is held.</b> The client answers null
 * when it could not read the provider, and this class simply keeps the previous
 * table — so a provider outage leaves yesterday's rates converting budgets
 * instead of blanking every total on the install. Rates a day old are a rounding
 * difference; no rates at all is a broken page.
 */
@Service
public class RatesService {

    private static final Logger log = LoggerFactory.getLogger(RatesService.class);

    private final ExchangeRatesClient client;
    private final RatesRepository repository;
    private final AtomicReference<RateTable> held = new AtomicReference<>();

    public RatesService(ExchangeRatesClient client, RatesRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    /** Never null. An empty table when nothing has ever been fetched. */
    public RateTable current() {
        RateTable table = held.get();
        if (table != null) return table;
        // First read after a restart: whatever the last refresh persisted.
        RateTable stored = repository.load();
        held.compareAndSet(null, stored);
        return held.get();
    }

    /**
     * Fetches and stores a new table. Returns true when the rates changed
     * hands, false when the provider could not be read.
     *
     * Called by the scheduled refresh and once at startup — never from a
     * request, so no page ever waits on the provider.
     */
    public boolean refresh() {
        RateTable fetched = client.fetch();
        if (fetched == null) {
            RateTable existing = current();
            log.warn("Keeping the {} rate table already held ({})",
                    existing.isEmpty() ? "empty" : "previous",
                    existing.isEmpty() ? "nothing fetched yet" : existing.getDate());
            return false;
        }
        held.set(fetched);
        repository.save(fetched);
        return true;
    }
}
