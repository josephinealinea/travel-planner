package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.rates.api.RatesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * When rates get fetched: once shortly after the service starts, and then on a
 * schedule.
 *
 * <b>The startup refresh runs on its own thread.</b> Deliberately not on the
 * boot thread: the provider is somebody else's server, and a slow or hanging
 * one would otherwise add its whole timeout to every start of the app — or fail
 * the start outright. Booting must never depend on it, so the fetch is handed
 * to a separate thread and the app finishes coming up regardless. Until it
 * lands, {@link RatesService#current()} answers with whatever the last run
 * persisted to {@code data/rates.yml}, which after any normal restart is
 * yesterday's table rather than nothing.
 *
 * The cron default sits just after the provider's own daily update (it
 * publishes {@code time_next_update_utc} around 00:30 UTC); asking more often
 * than once a day returns the same numbers. Configurable as
 * {@code app.rates.cron}.
 *
 * <b>Neither is relied on where the CPU is not always there.</b> On free-tier
 * Cloud Run a background thread only runs during a request and a cron may
 * never fire, so {@link RatesService#current()} also refreshes a table that is
 * a day old when it is read. Both triggers here stay because they are harmless
 * where they do run, and mean a read there never has to wait.
 */
@Component
public class RatesRefresher {

    private static final Logger log = LoggerFactory.getLogger(RatesRefresher.class);

    private final RatesService rates;

    public RatesRefresher(RatesService rates) {
        this.rates = rates;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        CompletableFuture.runAsync(() -> {
            log.info("Refreshing exchange rates on startup");
            rates.refresh();
        });
    }

    @Scheduled(cron = "${app.rates.cron}", zone = "UTC")
    public void refreshOnSchedule() {
        log.info("Refreshing exchange rates on schedule");
        rates.refresh();
    }
}
