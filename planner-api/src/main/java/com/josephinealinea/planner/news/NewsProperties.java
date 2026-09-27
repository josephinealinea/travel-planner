package com.josephinealinea.planner.news;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.context.annotation.Configuration;

/**
 * Everything the news module can be told, under {@code app.news}, in a record
 * of its own rather than a component of AppProperties (a score of tests
 * construct that positionally). A missing key on either service means that
 * service is off, not that the app fails to start — see {@link Service#enabled()}.
 */
@ConfigurationProperties(prefix = "app.news")
public record NewsProperties(boolean enabled, double capFraction, int maxArticlesPerDestination,
                             Service newsdata, Service currents) {

    @ConstructorBinding
    public NewsProperties {
        if (capFraction <= 0) capFraction = 0.9;
        if (maxArticlesPerDestination <= 0) maxArticlesPerDestination = 3;
        if (newsdata == null) newsdata = new Service("https://newsdata.io", null, 200);
        if (currents == null) currents = new Service("https://api.currentsapi.services", null, 250);
    }

    /** The most calls a UTC day may spend against this service. */
    public int capFor(Service service) {
        return (int) Math.floor(service.dailyLimit() * capFraction);
    }

    /** One outside service: where it is, its key and its free-tier daily limit. */
    public record Service(String baseUrl, String key, int dailyLimit) {

        public boolean enabled() {
            return key != null && !key.isBlank();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NewsProperties.class)
    public static class Registration {
    }
}
