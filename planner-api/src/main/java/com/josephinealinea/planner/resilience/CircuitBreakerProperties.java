package com.josephinealinea.planner.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the {@link CircuitBreaker} treats a failing outside service, under
 * {@code app.circuit-breaker}. Plain values in application.yml, not environment
 * settings. A record of its own rather than a component of AppProperties, which
 * a score of tests construct positionally; every field may be absent, so the
 * breaker behaves the same with no block at all.
 *
 * A value that would quietly break the breaker is replaced, with a WARN naming
 * the property and what it became: a threshold below 1 becomes 3; a zero or
 * negative rung is dropped (a 0s pause would never open anything), and a ladder
 * left empty is the default one. The same holds inside a per-service override.
 *
 * @param failureThreshold consecutive failed calls before a service is paused
 * @param backOff the escalating pause, and the per-key retry delay; the last rung repeats
 * @param services per-service overrides by service name; an omitted field inherits the global one
 */
@ConfigurationProperties(prefix = "app.circuit-breaker")
public record CircuitBreakerProperties(Integer failureThreshold, List<Duration> backOff, Map<String, Service> services) {

    static final int DEFAULT_THRESHOLD = 3;
    static final List<Duration> DEFAULT_BACK_OFF =
            List.of(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerProperties.class);
    private static final String PREFIX = "app.circuit-breaker.";

    public CircuitBreakerProperties {
        failureThreshold = failureThreshold == null ? DEFAULT_THRESHOLD : threshold(PREFIX + "failure-threshold", failureThreshold);
        backOff = backOff == null ? DEFAULT_BACK_OFF : ladder(PREFIX + "back-off", backOff);
        Map<String, Service> sane = new LinkedHashMap<>();
        if (services != null) {
            services.forEach((name, o) -> {
                String at = PREFIX + "services." + name + ".";
                sane.put(name, o == null ? new Service(null, null) : new Service(
                        o.failureThreshold() == null ? null : threshold(at + "failure-threshold", o.failureThreshold()),
                        o.backOff() == null ? null : ladder(at + "back-off", o.backOff())));
            });
        }
        services = Map.copyOf(sane);
    }

    private static int threshold(String property, int value) {
        if (value >= 1) return value;
        log.warn("{} is {}, which is below 1; using {}", property, value, DEFAULT_THRESHOLD);
        return DEFAULT_THRESHOLD;
    }

    private static List<Duration> ladder(String property, List<Duration> value) {
        List<Duration> positive = value.stream().filter(d -> d != null && d.isPositive()).toList();
        if (positive.isEmpty()) {
            log.warn("{} is {}, which has no positive pause; using {}", property, value, DEFAULT_BACK_OFF);
            return DEFAULT_BACK_OFF;
        }
        if (positive.size() != value.size()) {
            log.warn("{} is {}; zero and negative pauses are dropped, using {}", property, value, positive);
        }
        return positive;
    }

    /** One service's overrides; either may be null, meaning "the global value". */
    public record Service(Integer failureThreshold, List<Duration> backOff) {}

    /** What one service actually runs with. */
    public record Settings(int failureThreshold, List<Duration> backOff) {

        /** The pause for the {@code n}th opening (1-based); the last rung repeats. */
        Duration rung(int n) {
            return backOff.get(Math.min(Math.max(n, 1), backOff.size()) - 1);
        }

        Duration longest() {
            return backOff.stream().max(Duration::compareTo).orElseThrow();
        }
    }

    public Settings settingsFor(String service) {
        Service override = services.get(service);
        int threshold = override != null && override.failureThreshold() != null
                ? override.failureThreshold() : failureThreshold;
        List<Duration> ladder = override != null && override.backOff() != null && !override.backOff().isEmpty()
                ? List.copyOf(override.backOff()) : backOff;
        return new Settings(threshold, ladder);
    }

    public static CircuitBreakerProperties defaults() {
        return new CircuitBreakerProperties(null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CircuitBreakerProperties.class)
    public static class Registration {
    }
}
