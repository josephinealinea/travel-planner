package com.josephinealinea.planner.trips.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.tiers.basic-max-trips} (env {@code BASIC_MAX_TRIPS}, default 3):
 * how many trips a BASIC account may be on, created and joined together.
 *
 * A record of its own rather than a component of AppProperties, which is
 * constructed positionally in a score of tests.
 */
@ConfigurationProperties(prefix = "app.tiers")
public record TripLimitProperties(Integer basicMaxTrips) {

    public static final int DEFAULT_BASIC_MAX_TRIPS = 3;

    public TripLimitProperties {
        if (basicMaxTrips == null || basicMaxTrips < 1) basicMaxTrips = DEFAULT_BASIC_MAX_TRIPS;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TripLimitProperties.class)
    public static class Registration {
    }
}
