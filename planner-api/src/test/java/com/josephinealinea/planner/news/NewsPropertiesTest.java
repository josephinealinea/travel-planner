package com.josephinealinea.planner.news;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NewsPropertiesTest {

    @Test
    void defaultsFillInWhenNothingIsConfigured() {
        NewsProperties props = new NewsProperties(true, 0, 0, null, null);

        assertThat(props.capFraction()).isEqualTo(0.9);
        assertThat(props.maxArticlesPerDestination()).isEqualTo(3);
        assertThat(props.newsdata().dailyLimit()).isEqualTo(200);
        assertThat(props.currents().dailyLimit()).isEqualTo(250);
        assertThat(props.newsdata().enabled()).isFalse(); // no key
    }

    @Test
    void capIsTheDailyLimitTimesTheFraction() {
        NewsProperties props = new NewsProperties(true, 0.9, 3,
                new NewsProperties.Service("https://newsdata.io", "k", 200),
                new NewsProperties.Service("https://api.currentsapi.services", "k", 250));

        assertThat(props.capFor(props.newsdata())).isEqualTo(180);
        assertThat(props.capFor(props.currents())).isEqualTo(225);
    }

    @Test
    void aServiceWithAKeyIsEnabled() {
        NewsProperties.Service service = new NewsProperties.Service("https://newsdata.io", "k", 200);
        assertThat(service.enabled()).isTrue();
        assertThat(new NewsProperties.Service("https://newsdata.io", null, 200).enabled()).isFalse();
        assertThat(new NewsProperties.Service("https://newsdata.io", " ", 200).enabled()).isFalse();
    }
}
