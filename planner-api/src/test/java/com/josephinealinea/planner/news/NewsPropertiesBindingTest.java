package com.josephinealinea.planner.news;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml really binds through Spring — the unit test on the record
 * alone (NewsPropertiesTest) always passes an explicit baseUrl, so it cannot
 * catch a yml block that never sets one. That gap was real: the first cut of
 * application.yml's app.news.newsdata/currents blocks had key and daily-limit
 * only, so the bound Service.baseUrl() was null and RestClient threw "URI
 * with undefined scheme" on every real call — caught by hand while manually
 * exercising the Destinations tab, not by any test until this one.
 */
@SpringBootTest(properties = "feature-enable-database=false")
class NewsPropertiesBindingTest {

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void yamlMode(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("app.bootstrap.owner-email", () -> "owner@example.com");
        registry.add("app.bootstrap.owner-password", () -> "password123");
    }

    @Autowired NewsProperties props;
    @Autowired ApplicationContext ctx;

    @Test
    void applicationYmlBindsABaseUrlForBothServices() {
        assertThat(props.newsdata().baseUrl()).isEqualTo("https://newsdata.io");
        assertThat(props.currents().baseUrl()).isEqualTo("https://api.currentsapi.services");
        assertThat(props.newsdata().dailyLimit()).isEqualTo(200);
        assertThat(props.currents().dailyLimit()).isEqualTo(250);
        assertThat(props.capFraction()).isEqualTo(0.9);
        assertThat(props.maxArticlesPerDestination()).isEqualTo(3);
        assertThat(ctx.getBean("newsDataHttp", RestClient.class)).isNotNull();
        assertThat(ctx.getBean("currentsHttp", RestClient.class)).isNotNull();
    }
}
