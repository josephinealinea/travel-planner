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
        Bootstrap bootstrap
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

    /** Seeds the very first account, because there is no self-signup. */
    public record Bootstrap(String ownerEmail, String ownerPassword) {}
}
