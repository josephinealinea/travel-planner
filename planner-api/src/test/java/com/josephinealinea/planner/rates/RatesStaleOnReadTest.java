package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refreshing the rate table when it is read and found a day old — the part of
 * {@link RatesService} that does not rely on a background thread or a cron,
 * because on free-tier Cloud Run neither can be relied on to run.
 *
 * Every test drives "now" through a {@link MutableClock} and answers fetches
 * from a {@link ScriptedClient}, so nothing here touches the network or waits
 * on real time.
 */
class RatesStaleOnReadTest {

    private static final Instant FETCHED = Instant.parse("2026-09-14T01:30:00Z");

    @Test
    void aTableADayOldIsRefreshedWhenRead() {
        MutableClock clock = new MutableClock(FETCHED.plus(Duration.ofHours(24)));
        ScriptedClient client = new ScriptedClient(clock).succeedWith("1.20");
        RatesService rates = new RatesService(client, repositoryHolding(table("1.10", FETCHED)), clock);

        RateTable read = rates.current();

        assertThat(client.calls).hasValue(1);
        assertThat(read.rateFor("USD")).isEqualByComparingTo("1.20");
        assertThat(read.getFetchedAt()).isEqualTo(clock.instant());
    }

    @Test
    void aFreshTableIsServedWithoutAsking() {
        MutableClock clock = new MutableClock(FETCHED.plus(Duration.ofHours(23)).plusSeconds(3599));
        ScriptedClient client = new ScriptedClient(clock).succeedWith("1.20");
        RatesService rates = new RatesService(client, repositoryHolding(table("1.10", FETCHED)), clock);

        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.10");
        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.10");
        assertThat(client.calls).hasValue(0);
    }

    @Test
    void aTableNeverFetchedIsFetchedOnFirstRead() {
        MutableClock clock = new MutableClock(FETCHED);
        ScriptedClient client = new ScriptedClient(clock).succeedWith("1.20");
        InMemoryRates repository = new InMemoryRates();
        RatesService rates = new RatesService(client, repository, clock);

        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.20");
        assertThat(client.calls).hasValue(1);
        assertThat(repository.saved).as("a refresh on read is persisted like any other").hasSize(1);
    }

    @Test
    void aFailedRefreshKeepsTheOldTableAndBacksOff() {
        MutableClock clock = new MutableClock(FETCHED.plus(Duration.ofDays(2)));
        ScriptedClient client = new ScriptedClient(clock).fail().succeedWith("1.20");
        RatesService rates = new RatesService(client, repositoryHolding(table("1.10", FETCHED)), clock);

        // The failed fetch leaves yesterday's rates converting.
        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.10");
        assertThat(client.calls).hasValue(1);

        // Within the back-off, a stale table is served without asking again —
        // otherwise every request during an outage would wait out the timeout.
        clock.advance(RatesService.RETRY_AFTER_FAILURE.minusSeconds(1));
        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.10");
        assertThat(client.calls).hasValue(1);

        // Once it has run out, the next read tries again.
        clock.advance(Duration.ofSeconds(1));
        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.20");
        assertThat(client.calls).hasValue(2);
    }

    @Test
    void aFailureFromTheCronAlsoStartsTheBackOff() {
        MutableClock clock = new MutableClock(FETCHED.plus(Duration.ofDays(2)));
        ScriptedClient client = new ScriptedClient(clock).fail();
        RatesService rates = new RatesService(client, repositoryHolding(table("1.10", FETCHED)), clock);

        assertThat(rates.refresh()).isFalse();
        rates.current();

        assertThat(client.calls).hasValue(1);
    }

    @Test
    void anEmptyTableThatCannotBeFetchedStaysEmptyAndBacksOff() {
        MutableClock clock = new MutableClock(FETCHED);
        ScriptedClient client = new ScriptedClient(clock).fail();
        RatesService rates = new RatesService(client, new InMemoryRates(), clock);

        assertThat(rates.current().isEmpty()).isTrue();
        assertThat(rates.current().isEmpty()).isTrue();
        assertThat(client.calls).hasValue(1);
    }

    /**
     * The one that matters under load: a burst of readers finding the table
     * stale causes one fetch, and every reader but the fetching one answers
     * straight away with the table already held.
     */
    @Test
    void concurrentReadersTriggerOneFetchAndDoNotWaitForIt() throws Exception {
        MutableClock clock = new MutableClock(FETCHED.plus(Duration.ofDays(2)));
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch releaseFetch = new CountDownLatch(1);
        ScriptedClient client = new ScriptedClient(clock).succeedWith("1.20");
        client.onFetch = () -> {
            fetchStarted.countDown();
            await(releaseFetch);
        };
        RatesService rates = new RatesService(client, repositoryHolding(table("1.10", FETCHED)), clock);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<RateTable> fetching = pool.submit(rates::current);
            assertThat(fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // While that fetch is held open, seven more readers arrive.
            List<Future<RateTable>> others = new ArrayList<>();
            for (int i = 0; i < 7; i++) others.add(pool.submit(rates::current));
            for (Future<RateTable> other : others) {
                // They answer without the fetch being released — so they
                // did not wait on it — and with the table already held.
                assertThat(other.get(5, TimeUnit.SECONDS).rateFor("USD")).isEqualByComparingTo("1.10");
            }

            releaseFetch.countDown();
            assertThat(fetching.get(5, TimeUnit.SECONDS).rateFor("USD")).isEqualByComparingTo("1.20");
        } finally {
            releaseFetch.countDown();
            pool.shutdownNow();
        }

        assertThat(client.calls).hasValue(1);
        assertThat(rates.current().rateFor("USD")).isEqualByComparingTo("1.20");
        assertThat(client.calls).as("fresh now: the next read does not fetch").hasValue(1);
    }

    // ── fixtures ─────

    private static RateTable table(String usd, Instant fetchedAt) {
        RateTable table = new RateTable();
        table.setBase("EUR");
        table.setDate("Mon, 14 Sep 2026 00:02:31 +0000");
        table.setFetchedAt(fetchedAt);
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        rates.put("EUR", BigDecimal.ONE);
        rates.put("USD", new BigDecimal(usd));
        table.setRates(rates);
        return table;
    }

    private static InMemoryRates repositoryHolding(RateTable table) {
        InMemoryRates repository = new InMemoryRates();
        repository.stored = table;
        return repository;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static AppProperties props() {
        return new AppProperties(
                new AppProperties.Storage(null),
                new AppProperties.Publish(null, null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
    }

    /** Answers fetches from a script — a table, or null for a failure — and counts them. */
    private static final class ScriptedClient extends ExchangeRatesClient {
        final AtomicInteger calls = new AtomicInteger();
        private final List<String> script = new ArrayList<>();
        private final Clock clock;
        volatile Runnable onFetch = () -> {};

        ScriptedClient(Clock clock) {
            super(RestClient.create(), props());
            this.clock = clock;
        }

        ScriptedClient succeedWith(String usd) { script.add(usd); return this; }
        ScriptedClient fail() { script.add(null); return this; }

        @Override
        public RateTable fetch() {
            calls.incrementAndGet();
            onFetch.run();
            String usd;
            synchronized (script) {
                usd = script.isEmpty() ? null : script.remove(0);
            }
            return usd == null ? null : table(usd, clock.instant());
        }
    }

    private static final class InMemoryRates implements RatesRepository {
        RateTable stored;
        final List<RateTable> saved = new ArrayList<>();

        @Override
        public RateTable load() {
            return stored == null ? new RateTable() : stored;
        }

        @Override
        public void save(RateTable table) {
            stored = table;
            saved.add(table);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) { this.now = now; }

        void advance(Duration by) { now = now.plus(by); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
