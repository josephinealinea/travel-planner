package com.josephinealinea.planner.geocoding;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeocodingConfig {

    @Bean
    RestClient countriesDevClient(AppProperties props) {
        var timeout = props.geocoding().timeout();
        var settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(props.geocoding().baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
