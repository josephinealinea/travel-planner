package com.josephinealinea.planner.flights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** application.yml really binds through Spring, and the two clients exist. */
@SpringBootTest(properties = "feature-enable-database=false")
class FlightPropertiesBindingTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void yamlMode(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("app.bootstrap.owner-email", () -> "owner@example.com");
        registry.add("app.bootstrap.owner-password", () -> "password123");
    }

    @Autowired FlightProperties props;
    @Autowired CircuitBreakerProperties breaker;
    @Autowired ApplicationContext ctx;

    @Test
    void applicationYmlBindsIntoTheRecord() {
        assertThat(props.aerodatabox().monthlyLimit()).isEqualTo(400);
        assertThat(props.aerodatabox().cap()).isEqualTo(360);
        assertThat(props.aerodatabox().baseUrl()).isEqualTo("https://aerodatabox.p.rapidapi.com");
        assertThat(props.aerodatabox().timeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(props.aviationstack().monthlyLimit()).isEqualTo(100);
        assertThat(props.aviationstack().cap()).isEqualTo(90);
        assertThat(props.aviationstack().baseUrl()).isEqualTo("http://api.aviationstack.com/v1");
        assertThat(props.ttl().farAhead()).isEqualTo(Duration.ofHours(72));
        assertThat(props.ttl().withinThreeDays()).isEqualTo(Duration.ofHours(24));
        assertThat(props.ttl().withinOneDay()).isEqualTo(Duration.ofHours(1));
        assertThat(props.live().window()).isEqualTo(Duration.ofHours(2));
        assertThat(props.live().ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.negativeTtl()).isEqualTo(Duration.ofHours(6));
        assertThat(props.prewarm().cron()).isEqualTo("0 0 1 * * *");
        assertThat(props.publicRefresh().windowBefore()).isEqualTo(Duration.ofDays(2));
        assertThat(props.publicRefresh().windowAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(props.publicRefresh().maxLiveAttemptsPerFlight()).isEqualTo(8);
        assertThat(props.publicRefresh().staleBrowserTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(ctx.getBean("aeroDataBoxHttp", RestClient.class)).isNotNull();
        assertThat(ctx.getBean("aviationStackHttp", RestClient.class)).isNotNull();
    }

    /** app.circuit-breaker binds from plain yml values, and the per-service example is only a comment. */
    @Test
    void theCircuitBreakerBlockBindsWithTheDocumentedDefaults() {
        assertThat(breaker.failureThreshold()).isEqualTo(3);
        assertThat(breaker.backOff()).containsExactly(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));
        assertThat(breaker.services()).isEmpty();
        assertThat(breaker.settingsFor("aviationstack").failureThreshold()).isEqualTo(3);
        assertThat(ctx.getBean(CircuitBreaker.class)).isNotNull();
    }

    /** The values come from application.yml itself, as plain values rather than environment placeholders. */
    @Test
    void theTunablesAreWrittenIntoApplicationYmlAsPlainValues() throws Exception {
        var yml = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yml")).get(0);

        assertThat(yml.getProperty("app.circuit-breaker.failure-threshold")).hasToString("3");
        assertThat(yml.getProperty("app.circuit-breaker.back-off[0]")).hasToString("15m");
        assertThat(yml.getProperty("app.circuit-breaker.back-off[2]")).hasToString("6h");
        assertThat(yml.getProperty("app.flights.public-refresh.window-before")).hasToString("2d");
        assertThat(yml.getProperty("app.flights.public-refresh.window-after")).hasToString("7d");
        assertThat(yml.getProperty("app.flights.public-refresh.max-live-attempts-per-flight")).hasToString("8");
        assertThat(yml.getProperty("app.flights.public-refresh.stale-browser-ttl")).hasToString("5m");
        assertThat(yml.getProperty("app.flights.aerodatabox.monthly-limit")).hasToString("400");
        assertThat(yml.getProperty("app.flights.prewarm.cron")).hasToString("0 0 1 * * *");
        // Only the two keys are environment settings.
        for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) yml).getPropertyNames()) {
            if (!name.startsWith("app.flights.") && !name.startsWith("app.circuit-breaker.")) continue;
            String value = String.valueOf(yml.getProperty(name));
            if (name.endsWith(".key")) continue;
            assertThat(value).as(name).doesNotContain("${");
        }
    }
}
