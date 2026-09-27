package com.josephinealinea.planner.flights;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Everything the flights module can be told, in a record of its own rather than
 * a component of AppProperties, which a score of tests construct positionally.
 * A missing key means that service is off, not that the app fails to start.
 */
@ConfigurationProperties(prefix = "app.flights")
public record FlightProperties(Service aerodatabox, Service aviationstack, Ttl ttl,
                               Live live, Duration negativeTtl, Prewarm prewarm, PublicRefresh publicRefresh) {

    /** The shape from before {@code publicRefresh}, which the tests construct positionally. */
    public FlightProperties(Service aerodatabox, Service aviationstack, Ttl ttl,
                            Live live, Duration negativeTtl, Prewarm prewarm) {
        this(aerodatabox, aviationstack, ttl, live, negativeTtl, prewarm, null);
    }

    // Two constructors: binding must be told which one is real, or it looks for a
    // no-argument one and startup dies with "No default constructor found".
    @ConstructorBinding
    public FlightProperties {
        if (aerodatabox == null) aerodatabox = new Service("https://aerodatabox.p.rapidapi.com", null, 400, 90, null);
        if (aviationstack == null) aviationstack = new Service("http://api.aviationstack.com/v1", null, 100, 90, null);
        if (ttl == null) ttl = new Ttl(null, null, null);
        if (live == null) live = new Live(null, null);
        if (negativeTtl == null) negativeTtl = Duration.ofHours(6);
        if (prewarm == null) prewarm = new Prewarm(null);
        if (publicRefresh == null) publicRefresh = new PublicRefresh(null, null, 0, null);
    }

    /** One outside service: where it is, its key, its free-tier limit and how much of it to use. */
    public record Service(String baseUrl, String key, int monthlyLimit, int capPercent, Duration timeout) {

        /** {@code capPercent} of zero or less means the default 90; above 100 is clamped to 100. */
        public Service {
            if (capPercent <= 0) capPercent = 90;
            if (capPercent > 100) capPercent = 100;
            if (timeout == null) timeout = Duration.ofSeconds(8);
        }

        /** The most calls a month may spend: the limit times the cap percentage. */
        public int cap() {
            return monthlyLimit * capPercent / 100;
        }

        public boolean enabled() {
            return key != null && !key.isBlank();
        }
    }

    /** How long AeroDataBox data is trusted, by how close the flight is. */
    public record Ttl(Duration farAhead, Duration withinThreeDays, Duration withinOneDay) {

        public Ttl {
            if (farAhead == null) farAhead = Duration.ofHours(72);
            if (withinThreeDays == null) withinThreeDays = Duration.ofHours(24);
            if (withinOneDay == null) withinOneDay = Duration.ofHours(1);
        }
    }

    /** AviationStack's extras: only inside the window before departure, and for this long. */
    public record Live(Duration window, Duration ttl) {

        public Live {
            if (window == null) window = Duration.ofHours(2);
            if (ttl == null) ttl = Duration.ofMinutes(15);
        }
    }

    /**
     * What a reader's refresh on a published page may cost. Only a flight
     * departing between {@code windowBefore} ago and {@code windowAfter} ahead is
     * looked up at all; AviationStack is tried at most
     * {@code maxLiveAttemptsPerFlight} times per operating flight and date; and a
     * page served something stale is told to ask again after
     * {@code staleBrowserTtl}. Zero or less keeps the default attempt count.
     */
    public record PublicRefresh(Duration windowBefore, Duration windowAfter, int maxLiveAttemptsPerFlight,
                                Duration staleBrowserTtl) {

        public PublicRefresh {
            if (windowBefore == null) windowBefore = Duration.ofDays(2);
            if (windowAfter == null) windowAfter = Duration.ofDays(7);
            if (maxLiveAttemptsPerFlight <= 0) maxLiveAttemptsPerFlight = 8;
            if (staleBrowserTtl == null) staleBrowserTtl = Duration.ofMinutes(5);
        }
    }

    public record Prewarm(String cron) {

        public Prewarm {
            if (cron == null || cron.isBlank()) cron = "0 0 1 * * *";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FlightProperties.class)
    public static class Registration {
    }
}
