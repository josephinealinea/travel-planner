package com.josephinealinea.planner.resilience;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** The yml shape documented in application.yml binds, per-service overrides included. */
class CircuitBreakerPropertiesBindingTest {

    private static CircuitBreakerProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("app.circuit-breaker", CircuitBreakerProperties.class);
    }

    @Test
    void anAbsentBlockIsTheDefaults() {
        CircuitBreakerProperties props = bind(Map.of());

        assertThat(props.failureThreshold()).isEqualTo(3);
        assertThat(props.backOff()).containsExactly(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));
    }

    @Test
    void aPerServiceOverrideBindsAndWins() {
        CircuitBreakerProperties props = bind(Map.of(
                "app.circuit-breaker.failure-threshold", "4",
                "app.circuit-breaker.back-off[0]", "1m",
                "app.circuit-breaker.back-off[1]", "2h",
                "app.circuit-breaker.services.aviationstack.failure-threshold", "2"));

        assertThat(props.settingsFor("aviationstack").failureThreshold()).isEqualTo(2);
        assertThat(props.settingsFor("aviationstack").backOff()).containsExactly(Duration.ofMinutes(1), Duration.ofHours(2));
        assertThat(props.settingsFor("aerodatabox").failureThreshold()).isEqualTo(4);
    }

    // ---- a value that would silently break the breaker is replaced, and says so ----

    private static final List<Duration> DEFAULT = List.of(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));

    /** Builds with the class's log captured; returns the WARN lines. */
    private static String warned(Supplier<CircuitBreakerProperties> build, CircuitBreakerProperties[] out) {
        Logger logger = (Logger) LoggerFactory.getLogger(CircuitBreakerProperties.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            out[0] = build.get();
        } finally {
            logger.detachAppender(appender);
        }
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            if (e.getLevel() == ch.qos.logback.classic.Level.WARN) all.append(e.getFormattedMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void nonPositiveRungsAreDroppedWithAWarning() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];
        String log = warned(() -> bind(Map.of(
                "app.circuit-breaker.back-off[0]", "0s",
                "app.circuit-breaker.back-off[1]", "-1m",
                "app.circuit-breaker.back-off[2]", "2h")), p);

        assertThat(p[0].backOff()).containsExactly(Duration.ofHours(2));
        assertThat(log).contains("app.circuit-breaker.back-off").contains("PT2H");
    }

    @Test
    void aLadderWithNothingPositiveLeftIsTheDefaultWithAWarning() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];
        String log = warned(() -> bind(Map.of("app.circuit-breaker.back-off[0]", "0s")), p);

        assertThat(p[0].backOff()).isEqualTo(DEFAULT);
        assertThat(log).contains("app.circuit-breaker.back-off");
    }

    @Test
    void anEmptyLadderIsTheDefaultWithAWarning() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];
        String log = warned(() -> new CircuitBreakerProperties(null, List.of(), null), p);

        assertThat(p[0].backOff()).isEqualTo(DEFAULT);
        assertThat(log).contains("app.circuit-breaker.back-off");
    }

    @Test
    void aThresholdBelowOneIsThreeWithAWarning() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];
        String log = warned(() -> bind(Map.of("app.circuit-breaker.failure-threshold", "0")), p);

        assertThat(p[0].failureThreshold()).isEqualTo(3);
        assertThat(log).contains("app.circuit-breaker.failure-threshold").contains("3");
    }

    @Test
    void anOverridesBadValuesAreReplacedWithAWarningToo() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];
        String log = warned(() -> bind(Map.of(
                "app.circuit-breaker.failure-threshold", "5",
                "app.circuit-breaker.services.aviationstack.failure-threshold", "-1",
                "app.circuit-breaker.services.aviationstack.back-off[0]", "0s")), p);

        assertThat(p[0].settingsFor("aviationstack").failureThreshold()).isEqualTo(3);
        assertThat(p[0].settingsFor("aviationstack").backOff()).isEqualTo(DEFAULT);
        assertThat(log).contains("app.circuit-breaker.services.aviationstack.failure-threshold")
                .contains("app.circuit-breaker.services.aviationstack.back-off");
    }

    @Test
    void goodValuesAndAnAbsentBlockWarnAboutNothing() {
        CircuitBreakerProperties[] p = new CircuitBreakerProperties[1];

        assertThat(warned(() -> bind(Map.of()), p)).isEmpty();
        assertThat(warned(() -> bind(Map.of("app.circuit-breaker.back-off[0]", "1m",
                "app.circuit-breaker.services.aviationstack.failure-threshold", "2")), p)).isEmpty();
    }
}
