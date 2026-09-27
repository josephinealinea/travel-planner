package com.josephinealinea.planner.flights;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.josephinealinea.planner.shared.HttpCallLog;
import org.springframework.web.client.RestClient;

/**
 * Two clients, one per outside service. Named {@code …Http} on purpose: a
 * {@code @Bean RestClient aeroDataBoxClient} would collide with the
 * {@code @Component} of the same name and startup would die (see CLAUDE.md).
 * No status handlers: the clients read the status themselves, because an empty
 * body, a 429 and a 403 all mean different things.
 */
@Configuration
public class FlightsConfig {

    @Bean
    RestClient aeroDataBoxHttp(FlightProperties props) {
        return build(props.aerodatabox(), "AeroDataBox");
    }

    @Bean
    RestClient aviationStackHttp(FlightProperties props) {
        return build(props.aviationstack(), "AviationStack");
    }

    private static RestClient build(FlightProperties.Service service, String name) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(service.timeout())
                .withReadTimeout(service.timeout());
        return HttpCallLog.on(RestClient.builder(), name)
                .baseUrl(service.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "travel-planner/1.0")
                .build();
    }
}
