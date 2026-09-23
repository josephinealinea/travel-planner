package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

/**
 * Same shape as GeocodingConfig and WeatherConfig. @EnableScheduling is here
 * rather than on the application class because this is the only thing in the
 * app that schedules anything — see RatesRefresher.
 */
@Configuration
@EnableScheduling
public class RatesConfig {

    @Bean
    RestClient erApiClient(AppProperties props) {
        var timeout = props.rates().timeout();
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(props.rates().baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
