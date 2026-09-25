package com.josephinealinea.planner.weather;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * {@code app.weather.today.cache-ttl} — how long a reading for <b>today's
 * date</b> is trusted before it is looked up again.
 *
 * Later dates keep {@code app.weather.cache-ttl}; past dates are never
 * refetched at all. Today's forecast is the one that changes during the day and
 * the one a traveller reads most, so it has a setting of its own. Both default
 * to 12 hours, which makes this a no-op until somebody chooses otherwise.
 *
 * A record of its own rather than a component of AppProperties, which is
 * constructed positionally in a score of tests.
 */
@ConfigurationProperties(prefix = "app.weather.today")
public record TodayWeatherProperties(Duration cacheTtl) {

    public TodayWeatherProperties {
        if (cacheTtl == null) cacheTtl = Duration.ofHours(12);
    }

    public static TodayWeatherProperties defaults() {
        return new TodayWeatherProperties(null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TodayWeatherProperties.class)
    public static class Registration {
    }
}
