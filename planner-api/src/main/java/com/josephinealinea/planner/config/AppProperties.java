package com.josephinealinea.planner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Everything under the "app." prefix in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Storage storage,
        Publish publish,
        Mail mail,
        Security security,
        Cors cors,
        Geocoding geocoding,
        Weather weather,
        Rates rates,
        Bootstrap bootstrap,
        Currencies currencies
) {
    public record Storage(String root) {
        public Storage {
            if (root == null || root.isBlank()) root = "./data";
        }
    }

    public record Publish(String dir, String publicBaseUrl) {
        public Publish {
            if (dir == null || dir.isBlank()) dir = "./data/published";
            if (publicBaseUrl == null || publicBaseUrl.isBlank()) publicBaseUrl = "http://localhost:8080/p";
        }
    }

    /** mode is one of log | file | smtp. */
    public record Mail(String mode, String from) {
        public Mail {
            if (mode == null || mode.isBlank()) mode = "file";
            if (from == null || from.isBlank()) from = "no-reply@travel-planner.local";
        }
    }

    public record Security(String jwtSecret, Duration sessionTtl, boolean cookieSecure) {
        public Security {
            if (sessionTtl == null) sessionTtl = Duration.ofHours(12);
        }
    }

    public record Cors(List<String> allowedOrigins) {
        public Cors {
            if (allowedOrigins == null || allowedOrigins.isEmpty()) {
                allowedOrigins = List.of("http://localhost:3000");
            }
        }
    }

    public record Geocoding(String baseUrl, Duration timeout, int cityLimit, int placeLimit) {
        public Geocoding {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://countries.dev";
            if (timeout == null) timeout = Duration.ofSeconds(5);
            if (cityLimit <= 0) cityLimit = 8;
            if (placeLimit <= 0) placeLimit = 20;
        }
    }

    /**
     * Open-Meteo, which is free and needs no key. Two base URLs because two
     * endpoints are needed and they live on different hosts: the forecast
     * endpoint only serves a window of about a fortnight either side of today
     * and answers 400 for anything else, and a trip is usually planned further
     * out than that, so dates outside the window come from the climate
     * endpoint instead. See WeatherClient.
     *
     * horizonDays / historyDays are how far the forecast endpoint is trusted
     * ahead and behind. They are deliberately a little tighter than what it
     * actually serves — the real window slides with today's date, and asking
     * one day too far is a 400 for the whole call rather than a gap.
     */
    public record Weather(String forecastBaseUrl,
                          String climateBaseUrl,
                          Duration timeout,
                          String climateModel,
                          int horizonDays,
                          int historyDays,
                          Duration cacheTtl) {
        // One constructor only — see the note on Currencies.
        public Weather {
            if (forecastBaseUrl == null || forecastBaseUrl.isBlank()) {
                forecastBaseUrl = "https://api.open-meteo.com/v1";
            }
            if (climateBaseUrl == null || climateBaseUrl.isBlank()) {
                climateBaseUrl = "https://climate-api.open-meteo.com/v1";
            }
            if (timeout == null) timeout = Duration.ofSeconds(8);
            // CMIP6 downscaled to 10km, daily, 1950-2050. Covers any date a
            // trip could plausibly carry.
            if (climateModel == null || climateModel.isBlank()) climateModel = "MRI_AGCM3_2_S";
            if (horizonDays <= 0) horizonDays = 14;
            if (historyDays <= 0) historyDays = 85;
            // Weather is never written to disk, so this cache is the only thing
            // standing between a page load and a round trip to Open-Meteo.
            // Process-local like TripLocks, which means a restart empties it —
            // deliberately accepted: the alternative was persisting readings,
            // and a cache that can be rebuilt in one call is not worth a file.
            if (cacheTtl == null) cacheTtl = Duration.ofHours(12);
        }
    }

    /**
     * Daily exchange rates, fetched for the whole install.
     *
     * The provider (open.er-api.com) is free and keyless and updates once a
     * day, publishing its own next-update time around 00:30 UTC — so the
     * default cron sits just after that, and asking more often returns the same
     * numbers. The cron runs in UTC for the same reason.
     *
     * `base` is the table's anchor: every rate is stored as units per one unit
     * of it, and cross-rates are derived. Changing it changes what gets
     * fetched, not what any trip displays.
     *
     * The currency *list* is deliberately not here — it is
     * `app.currencies.available`, which is already the one catalogue the whole
     * app selects from, and rates are fetched for exactly it.
     */
    public record Rates(String baseUrl, String base, String cron, Duration timeout) {
        // One constructor only — see the note on Currencies.
        public Rates {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://open.er-api.com";
            if (base == null || base.isBlank()) base = "EUR";
            // 01:30 UTC daily: an hour after the provider's own refresh.
            if (cron == null || cron.isBlank()) cron = "0 30 1 * * *";
            if (timeout == null) timeout = Duration.ofSeconds(10);
        }
    }

    /** Seeds the very first account, because there is no self-signup. */
    public record Bootstrap(String ownerEmail, String ownerPassword) {}

    /**
     * The currency list a brand-new account starts with, and the display
     * currency it starts with. Each user can freely override either from
     * Account afterward — this only decides what a fresh account is seeded
     * with. defaultDisplay has nothing to do with a trip's own displayCurrency
     * (the anchor its exchange-rate table is quoted against); it seeds the
     * user's own preference for the currency their budget totals show in.
     */
    /**
     * defaults  — what a new account starts with.
     * available — the whole catalogue a member can choose from.
     * Anything in defaults is offered whether or not the catalogue lists it, so
     * a deployment cannot seed accounts with a currency nobody can pick again.
     */
    public record Currencies(List<String> defaults, String defaultDisplay, List<String> available) {
        // One constructor only. Spring binds a record by its canonical
        // constructor and needs a single unambiguous candidate; the
        // convenience overloads that used to live here silently stopped
        // app.currencies binding at all, leaving it null at runtime.
        public Currencies {
            if (defaults == null || defaults.isEmpty()) defaults = List.of("EUR", "USD", "SGD");
            if (defaultDisplay == null || defaultDisplay.isBlank()) defaultDisplay = "EUR";

            if (available == null || available.isEmpty()) {
                available = List.of("AUD", "CAD", "CHF", "CNY", "EUR", "GBP", "JPY",
                                    "NZD", "SEK", "SGD", "USD");
            }
            List<String> merged = new java.util.ArrayList<>(available);
            for (String code : defaults) if (!merged.contains(code)) merged.add(code);
            if (!merged.contains(defaultDisplay)) merged.add(defaultDisplay);
            merged.sort(String::compareTo);
            available = List.copyOf(merged);
        }

    }
}
