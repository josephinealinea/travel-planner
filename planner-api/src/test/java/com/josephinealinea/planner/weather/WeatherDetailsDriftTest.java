package com.josephinealinea.planner.weather;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * page.js is a static file with its own copy of the request, because it looks
 * the weather up in the reader's browser. If a field is added to DetailField
 * and forgotten there, the planner shows it and a published page silently does
 * not. This fails instead.
 */
class WeatherDetailsDriftTest {

    private static String page() throws IOException {
        return new String(WeatherDetailsDriftTest.class.getResourceAsStream("/publish/page.js")
                .readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void publishedPageAsksForEveryDetailTheApiDoes() throws IOException {
        String page = page();
        for (DetailField field : DetailField.values()) {
            assertThat(page).as("page.js should request " + field.parameter()).contains(field.parameter());
            assertThat(page).as("page.js should read key " + field.key()).contains(field.key());
        }
    }

    @Test
    void publishedPageDoesNotAskTheClimateEndpointForForecastOnlyFields() throws IOException {
        String page = page();
        int at = page.indexOf("var CLIMATE_DAILY");
        assertThat(at).as("page.js should declare var CLIMATE_DAILY").isNotNegative();
        String climateList = page.substring(at, page.indexOf(";", at));
        for (DetailField field : DetailField.values()) {
            if (field.inClimate()) {
                assertThat(climateList).contains(field.parameter());
            } else {
                assertThat(climateList).doesNotContain(field.parameter());
            }
        }
    }
}
