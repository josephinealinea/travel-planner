package com.josephinealinea.planner.rates.api;

import com.josephinealinea.planner.rates.ExchangeRatesClient;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
 *
 * <h2>Refreshing when read</h2>
 * The cron and the startup fetch in {@code RatesRefresher} assume a CPU that is
 * always there. On free-tier Cloud Run it is only there during a request, so a
 * background thread can be frozen mid-fetch and a 01:30 cron may simply never
 * fire. {@link #current()} therefore also checks the table's age: one that was
 * never fetched, or was fetched {@link #MAX_AGE} or longer ago, is refreshed
 * there and then, synchronously, before the read answers. Three rules keep that
 * from costing more than one request its latency:
 *
 * <ul>
 *   <li><b>One refresh at a time.</b> The reader that finds the table stale
 *       takes {@link #refreshing} with {@code tryLock}; every reader that
 *       arrives meanwhile fails the try and answers immediately with the table
 *       already held. Only one of them ever waits on the provider.</li>
 *   <li><b>A failure backs off for {@link #RETRY_AFTER_FAILURE}.</b> Without
 *       it a provider outage would add the whole client timeout to
 *       <em>every</em> request, since a stale table stays stale until a fetch
 *       succeeds. A failure from any caller — the cron included — starts the
 *       back-off.</li>
 *   <li><b>The staleness is measured on {@code fetchedAt}</b> — when this app
 *       last succeeded — not on the provider's own {@code date}, which only
 *       moves once a day however often it is asked.</li>
 * </ul>
 *
 * The cron and the startup fetch stay: where the CPU is always on they keep
 * the table fresh before anybody reads it, and a read then never has to wait.
 */
@Service
public class RatesService {

    private static final Logger log = LoggerFactory.getLogger(RatesService.class);

    /** A table this old is refreshed on the next read. The provider updates daily. */
    public static final Duration MAX_AGE = Duration.ofHours(24);

    /** After a failed fetch, reads do not try again for this long. */
    public static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(15);

    private final ExchangeRatesClient client;
    private final RatesRepository repository;
    private final Clock clock;
    private final AtomicReference<RateTable> held = new AtomicReference<>();

    /** Held by whichever caller is fetching; readers only ever tryLock it. */
    private final ReentrantLock refreshing = new ReentrantLock();

    /** When the last fetch failed, or null when the last one succeeded. */
    private volatile Instant lastFailureAt;

    // @Autowired because there are two constructors. Without it Spring cannot
    // choose, falls back to looking for a no-arg one, and the app fails to
    // start — see CLAUDE.md, Traps.
    @Autowired
    public RatesService(ExchangeRatesClient client, RatesRepository repository) {
        this(client, repository, Clock.systemUTC());
    }

    /**
     * Clock injected so a test can decide how old a table is and whether a
     * back-off has run out — both rules turn on "now".
     */
    public RatesService(ExchangeRatesClient client, RatesRepository repository, Clock clock) {
        this.client = client;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Never null. An empty table when nothing has ever been fetched and the
     * provider cannot be read either.
     *
     * Refreshes first when the table held is stale — see the class comment —
     * unless another caller is already refreshing, or the last attempt failed
     * less than {@link #RETRY_AFTER_FAILURE} ago.
     */
    public RateTable current() {
        RateTable table = held();
        if (!isStale(table) || backingOff()) return table;
        if (!refreshing.tryLock()) return table;   // somebody else is fetching
        try {
            // Re-checked under the lock: the caller that held it a moment ago
            // may have just refreshed, or just failed.
            if (isStale(held()) && !backingOff()) {
                log.info("Exchange rates are stale ({}); refreshing on read", describe(held()));
                refresh();
            }
        } finally {
            refreshing.unlock();
        }
        return held();
    }

    /**
     * Fetches and stores a new table. Returns true when the rates changed
     * hands, false when the provider could not be read.
     *
     * Called by the scheduled refresh, once at startup, and by
     * {@link #current()} when the table is stale. It waits for any refresh
     * already under way rather than running beside it.
     */
    public boolean refresh() {
        refreshing.lock();
        try {
            RateTable fetched = client.fetch();
            if (fetched == null) {
                lastFailureAt = clock.instant();
                RateTable existing = held();
                log.warn("Keeping the {} rate table already held ({}); not retrying on read for {}",
                        existing.isEmpty() ? "empty" : "previous",
                        existing.isEmpty() ? "nothing fetched yet" : existing.getDate(),
                        RETRY_AFTER_FAILURE);
                return false;
            }
            lastFailureAt = null;
            held.set(fetched);
            repository.save(fetched);
            return true;
        } finally {
            refreshing.unlock();
        }
    }

    /** The table in memory, loading what the last refresh persisted on first use. */
    private RateTable held() {
        RateTable table = held.get();
        if (table != null) return table;
        // First read after a restart: whatever the last refresh persisted.
        held.compareAndSet(null, repository.load());
        return held.get();
    }

    /** Never fetched, or fetched {@link #MAX_AGE} ago or more. */
    private boolean isStale(RateTable table) {
        Instant fetchedAt = table.getFetchedAt();
        if (table.isEmpty() || fetchedAt == null) return true;
        return !clock.instant().isBefore(fetchedAt.plus(MAX_AGE));
    }

    private boolean backingOff() {
        Instant failedAt = lastFailureAt;
        return failedAt != null && clock.instant().isBefore(failedAt.plus(RETRY_AFTER_FAILURE));
    }

    private static String describe(RateTable table) {
        return table.getFetchedAt() == null ? "never fetched" : "fetched " + table.getFetchedAt();
    }
}
