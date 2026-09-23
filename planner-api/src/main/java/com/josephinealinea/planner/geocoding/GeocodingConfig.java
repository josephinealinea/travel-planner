package com.josephinealinea.planner.geocoding;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class GeocodingConfig {

    @Bean
    RestClient countriesDevClient(AppProperties props) {
        var timeout = props.geocoding().timeout();
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(props.geocoding().baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
