package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Two clients because Open-Meteo serves the forecast and the climate model from
 * different hosts. Same shape as GeocodingConfig otherwise, plus one header
 * that is not optional — see USER_AGENT.
 */
@Configuration
public class WeatherConfig {

    /**
     * Not required, but the right thing to send a free, keyless service: it
     * says which app the traffic is coming from.
     *
     * Explicitly *not* a fix for anything. An earlier version of this comment
     * claimed the default {@code Java/21.x} agent was being tarpitted, because
     * swapping it appeared to turn timeouts into responses. That was a
     * coincidence — see the rate-limit note on WeatherClient. Every agent
     * behaves identically once the limiter is tripped.
     */
    private static final String USER_AGENT = "travel-planner/1.0";

    @Bean
    RestClient openMeteoForecastClient(AppProperties props) {
        return build(props.weather().forecastBaseUrl(), props);
    }

    @Bean
    RestClient openMeteoClimateClient(AppProperties props) {
        return build(props.weather().climateBaseUrl(), props);
    }

    private static RestClient build(String baseUrl, AppProperties props) {
        var timeout = props.weather().timeout();
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", USER_AGENT)
                // No status handlers: WeatherClient reads the status itself,
                // because Open-Meteo's 429 and its 400-with-a-reason mean
                // different things and an exception loses both.
                .build();
    }
}
