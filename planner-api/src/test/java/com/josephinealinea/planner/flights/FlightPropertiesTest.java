package com.josephinealinea.planner.flights;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class FlightPropertiesTest {

    @Test
    void defaultsMatchTheAgreedTiersAndQuotas() {
        FlightProperties props = new FlightProperties(null, null, null, null, null, null);

        assertThat(props.aerodatabox().monthlyLimit()).isEqualTo(400);
        assertThat(props.aerodatabox().cap()).isEqualTo(360);
        assertThat(props.aviationstack().monthlyLimit()).isEqualTo(100);
        assertThat(props.aviationstack().cap()).isEqualTo(90);
        assertThat(props.ttl().farAhead()).isEqualTo(Duration.ofHours(72));
        assertThat(props.ttl().withinThreeDays()).isEqualTo(Duration.ofHours(24));
        assertThat(props.ttl().withinOneDay()).isEqualTo(Duration.ofHours(1));
        assertThat(props.live().window()).isEqualTo(Duration.ofHours(2));
        assertThat(props.live().ttl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.prewarm().cron()).isEqualTo("0 0 1 * * *");
        assertThat(props.publicRefresh().windowBefore()).isEqualTo(Duration.ofDays(2));
        assertThat(props.publicRefresh().windowAfter()).isEqualTo(Duration.ofDays(7));
        assertThat(props.publicRefresh().maxLiveAttemptsPerFlight()).isEqualTo(8);
        assertThat(props.publicRefresh().staleBrowserTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void anAbsentOrZeroPublicRefreshSettingKeepsItsDefault() {
        var refresh = new FlightProperties.PublicRefresh(null, Duration.ofDays(3), 0, null);

        assertThat(refresh.windowBefore()).isEqualTo(Duration.ofDays(2));
        assertThat(refresh.windowAfter()).isEqualTo(Duration.ofDays(3));
        assertThat(refresh.maxLiveAttemptsPerFlight()).isEqualTo(8);
        assertThat(refresh.staleBrowserTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aServiceWithNoKeyIsDisabledNotBroken() {
        FlightProperties props = new FlightProperties(null, null, null, null, null, null);

        assertThat(props.aerodatabox().enabled()).isFalse();
        assertThat(new FlightProperties.Service("http://x", "k", 10, 50, null).cap()).isEqualTo(5);
    }

    /** F6: a cap above the whole limit is the whole limit; zero or less keeps the 90 % default. */
    @Test
    void capPercentIsClampedToOneToAHundred() {
        assertThat(new FlightProperties.Service("http://x", "k", 400, 250, null).capPercent()).isEqualTo(100);
        assertThat(new FlightProperties.Service("http://x", "k", 400, 250, null).cap()).isEqualTo(400);
        assertThat(new FlightProperties.Service("http://x", "k", 400, 100, null).capPercent()).isEqualTo(100);
        assertThat(new FlightProperties.Service("http://x", "k", 400, 1, null).capPercent()).isEqualTo(1);
        assertThat(new FlightProperties.Service("http://x", "k", 400, 0, null).capPercent()).isEqualTo(90);
        assertThat(new FlightProperties.Service("http://x", "k", 400, -5, null).capPercent()).isEqualTo(90);
    }
}
