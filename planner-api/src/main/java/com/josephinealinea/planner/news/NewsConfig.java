package com.josephinealinea.planner.news;

import com.josephinealinea.planner.shared.HttpCallLog;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Two clients, one per outside service. Named {@code …Http} on purpose: a
 * {@code @Bean RestClient newsDataClient} would collide with the
 * {@code @Service} of the same name and startup would die (see CLAUDE.md).
 */
@Configuration
public class NewsConfig {

    @Bean
    RestClient newsDataHttp(NewsProperties props) {
        return build(props.newsdata(), "NewsData");
    }

    @Bean
    RestClient currentsHttp(NewsProperties props) {
        return build(props.currents(), "Currents");
    }

    private static RestClient build(NewsProperties.Service service, String name) {
        var settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(8))
                .withReadTimeout(Duration.ofSeconds(8));
        return HttpCallLog.on(RestClient.builder(), name)
                .baseUrl(service.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "travel-planner/1.0")
                .build();
    }
}
