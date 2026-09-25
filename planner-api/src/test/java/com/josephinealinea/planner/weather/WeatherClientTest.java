package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading Open-Meteo, without Open-Meteo.
 *
 * Fetching and parsing only — the cache moved to WeatherService, which is the
 * layer that knows which trip it belongs to, so the tests for it live in
 * WeatherServiceTest.
 *
 * The bodies below are real responses, captured live. What is worth pinning
 * down is the handful of things that are true of the live service and easy to
 * get wrong from the docs alone:
 *
 * <ul>
 *   <li>the forecast endpoint only serves a window around today, so which
 *       endpoint a date goes to depends on the clock;</li>
 *   <li>one call covers every destination — the service throttles bursts, so
 *       a call per destination is the thing to avoid, and the answer comes
 *       back as an array aligned to the coordinates asked for;</li>
 *   <li>a single coordinate answers with a bare object rather than a
 *       one-element array;</li>
 *   <li>the climate endpoint accepts weather_code and answers with nulls;</li>
 *   <li>a 429 is not retried but a 500 is, because one is the service asking
 *       to be left alone and the other is worth one more try.</li>
 * </ul>
 */
class WeatherClientTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-09-14");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-14T12:00:00Z"), ZoneOffset.UTC);

    private static final String TWO_LOCATIONS_CLIMATE = """
            [
              {"latitude":34.1,"longitude":-118.2,"timezone":"America/Los_Angeles",
               "daily":{"time":["2026-10-24","2026-10-25"],
                        "temperature_2m_max":[26.1,24.9],
                        "temperature_2m_min":[10.7,11.2],
                        "precipitation_sum":[0.0,0.4]}},
              {"latitude":-13.5,"longitude":-72.0,"timezone":"America/Lima",
               "daily":{"time":["2026-10-24","2026-10-25"],
                        "temperature_2m_max":[16.4,15.4],
                        "temperature_2m_min":[9.9,8.7],
                        "precipitation_sum":[5.16,0.74]}}
            ]
            """;

    /** A single coordinate really does come back unwrapped. */
    private static final String ONE_LOCATION_CLIMATE = """
            {"latitude":-13.5,"longitude":-72.0,"timezone":"America/Lima",
             "daily":{"time":["2026-10-24"],
                      "temperature_2m_max":[16.4],
                      "temperature_2m_min":[9.9],
                      "precipitation_sum":[5.16]}}
            """;

    /** weather_code is accepted by the climate endpoint and answered with nulls. */
    private static final String CLIMATE_WITH_NULL_CODES = """
            {"latitude":-13.5,"longitude":-72.0,
             "daily":{"time":["2026-10-24"],
                      "weather_code":[null],
                      "temperature_2m_max":[16.4],
                      "temperature_2m_min":[9.9],
                      "precipitation_sum":[5.16]}}
            """;

    private static final String FORECAST_TOMORROW = """
            {"latitude":-13.53,"longitude":-71.95,"timezone":"America/Lima",
             "daily":{"time":["2026-09-15"],
                      "weather_code":[61],
                      "temperature_2m_max":[18.2],
                      "temperature_2m_min":[4.1],
                      "precipitation_sum":[2.1]}}
            """;

    private static AppProperties props() {
        return new AppProperties(
                new AppProperties.Storage(null),
                new AppProperties.Publish(null, null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
    }

    private static WeatherClient client(CannedHttp forecast, CannedHttp climate) {
        return new WeatherClient(forecast.client(), climate.client(), props(), CLOCK);
    }


    private static final WeatherClient.Point LA =
            new WeatherClient.Point("la", 34.05223, -118.24368);
    private static final WeatherClient.Point CUSCO =
            new WeatherClient.Point("cusco", -13.53188, -71.96701);

    @Test
    void everyDestinationIsOneCallAndTheAnswerIsMatchedByPosition() {
        CannedHttp climate = new CannedHttp().ok(TWO_LOCATIONS_CLIMATE);
        CannedHttp forecast = new CannedHttp();

        Map<String, Map<LocalDate, WeatherClient.Reading>> readings =
                client(forecast, climate).lookUp(List.of(LA, CUSCO),
                        LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-25"));

        // The whole point: two destinations, one outbound call.
        assertThat(climate.callCount()).isEqualTo(1);
        assertThat(forecast.callCount()).isZero();

        assertThat(climate.asked().get(0).toString())
                .contains("latitude=34.05223,-13.53188")
                .contains("longitude=-118.24368,-71.96701");

        // Los Angeles is warm and dry, Cusco is cool and wet — so a swapped
        // mapping would be obvious rather than plausible.
        assertThat(readings.get("la").get(LocalDate.parse("2026-10-24")).temperatureMax())
                .isEqualTo(26.1);
        assertThat(readings.get("cusco").get(LocalDate.parse("2026-10-24")).temperatureMax())
                .isEqualTo(16.4);
        assertThat(readings.get("cusco").get(LocalDate.parse("2026-10-25")).precipitation())
                .isEqualTo(0.74);
    }

    @Test
    void aSingleCoordinateAnswersUnwrappedAndIsStillRead() {
        CannedHttp climate = new CannedHttp().ok(ONE_LOCATION_CLIMATE);

        var readings = client(new CannedHttp(), climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"));

        assertThat(readings.get("cusco")).hasSize(1);
        assertThat(readings.get("cusco").get(LocalDate.parse("2026-10-24")).temperatureMax())
                .isEqualTo(16.4);
    }

    private static final String FORECAST_WITH_DETAILS = """
            {"latitude":-13.53,"longitude":-71.95,"timezone":"America/Lima",
             "daily":{"time":["2026-09-15"],
                      "weather_code":[3],
                      "temperature_2m_max":[19.3],
                      "temperature_2m_min":[3.2],
                      "precipitation_sum":[0.0],
                      "sunrise":["2026-09-15T05:35"],
                      "sunset":["2026-09-15T17:43"],
                      "uv_index_max":[10.35],
                      "rain_sum":[0.0],
                      "precipitation_probability_max":[0]}}
            """;

    private static final String CLIMATE_WITH_NULL_DETAILS = """
            {"latitude":-13.5,"longitude":-72.0,
             "daily":{"time":["2026-10-24"],
                      "temperature_2m_max":[16.4],
                      "temperature_2m_min":[9.9],
                      "precipitation_sum":[5.16],
                      "uv_index_max":[null],
                      "wind_speed_10m_max":[10.1]}}
            """;

    private static String dailyOf(java.net.URI uri) {
        for (String pair : uri.getRawQuery().split("&")) {
            if (pair.startsWith("daily=")) return java.net.URLDecoder.decode(pair.substring(6),
                    java.nio.charset.StandardCharsets.UTF_8);
        }
        return "";
    }

    @Test
    void theForecastCallAsksForEveryDetailAndTheClimateCallOnlyForThoseItCanAnswer() {
        String climateSpanning = """
                {"daily":{"time":["2026-09-15","2026-10-24"],
                          "temperature_2m_max":[1.0,16.4],
                          "temperature_2m_min":[1.0,9.9],
                          "precipitation_sum":[1.0,5.16]}}
                """;
        CannedHttp forecast = new CannedHttp().ok(FORECAST_TOMORROW);
        CannedHttp climate = new CannedHttp().ok(climateSpanning);

        client(forecast, climate)
                .lookUp(List.of(CUSCO), TODAY.plusDays(1), LocalDate.parse("2026-10-24"));

        assertThat(dailyOf(forecast.asked().get(0)))
                .contains("uv_index_max", "sunrise", "wind_direction_10m_dominant");
        assertThat(dailyOf(climate.asked().get(0)))
                .contains("sunrise", "rain_sum", "cloud_cover_mean")
                .doesNotContain("uv_index_max", "apparent_temperature_max",
                        "precipitation_probability_max", "wind_direction_10m_dominant");
    }

    @Test
    void detailsCarryTheValuesTheAnswerHadAndAZeroIsKept() {
        CannedHttp forecast = new CannedHttp().ok(FORECAST_WITH_DETAILS);

        var reading = client(forecast, new CannedHttp())
                .lookUp(List.of(CUSCO), TODAY.plusDays(1), TODAY.plusDays(1))
                .get("cusco").get(TODAY.plusDays(1));

        assertThat(reading.details())
                .containsEntry("sunrise", "2026-09-15T05:35")
                .containsEntry("uvIndexMax", 10.35)
                .containsEntry("rainSum", 0.0)
                .containsEntry("precipitationProbabilityMax", 0.0);
    }

    @Test
    void aNullDetailColumnLeavesTheKeyOutRatherThanZero() {
        CannedHttp climate = new CannedHttp().ok(CLIMATE_WITH_NULL_DETAILS);

        var reading = client(new CannedHttp(), climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"))
                .get("cusco").get(LocalDate.parse("2026-10-24"));

        assertThat(reading.details()).containsKey("windSpeedMax").doesNotContainKey("uvIndexMax");
    }

    @Test
    void datesBeyondTheForecastHorizonGoToTheClimateEndpoint() {
        CannedHttp climate = new CannedHttp().ok(ONE_LOCATION_CLIMATE);
        CannedHttp forecast = new CannedHttp();

        var readings = client(forecast, climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"));

        assertThat(forecast.callCount()).isZero();
        assertThat(climate.callCount()).isEqualTo(1);
        assertThat(readings.get("cusco").values())
                .allSatisfy(r -> assertThat(r.source()).isEqualTo(DayWeather.Source.CLIMATE));
    }

    @Test
    void datesInsideTheForecastHorizonGoToTheForecastEndpoint() {
        CannedHttp forecast = new CannedHttp().ok(FORECAST_TOMORROW);
        CannedHttp climate = new CannedHttp();

        var readings = client(forecast, climate)
                .lookUp(List.of(CUSCO), TODAY.plusDays(1), TODAY.plusDays(1));

        assertThat(forecast.callCount()).isEqualTo(1);
        assertThat(climate.callCount()).isZero();

        var reading = readings.get("cusco").get(TODAY.plusDays(1));
        assertThat(reading.source()).isEqualTo(DayWeather.Source.FORECAST);
        // Only the forecast endpoint carries a condition code.
        assertThat(reading.weatherCode()).isEqualTo(61);
    }

    /**
     * A trip that starts inside the forecast window and ends outside it needs
     * both, and the climate call spans the whole range rather than carving
     * around the window — so the guard that matters is that a real forecast is
     * not then overwritten by a projection for the same day.
     */
    @Test
    void aTripStraddlingTheHorizonKeepsTheForecastForTheDaysThatHaveOne() {
        String climateSpanning = """
                {"daily":{"time":["2026-09-15","2026-10-24"],
                          "temperature_2m_max":[99.0,16.4],
                          "temperature_2m_min":[99.0,9.9],
                          "precipitation_sum":[99.0,5.16]}}
                """;
        CannedHttp forecast = new CannedHttp().ok(FORECAST_TOMORROW);
        CannedHttp climate = new CannedHttp().ok(climateSpanning);

        var readings = client(forecast, climate)
                .lookUp(List.of(CUSCO), TODAY.plusDays(1), LocalDate.parse("2026-10-24"));

        assertThat(forecast.callCount()).isEqualTo(1);
        assertThat(climate.callCount()).isEqualTo(1);

        var tomorrow = readings.get("cusco").get(TODAY.plusDays(1));
        assertThat(tomorrow.source()).isEqualTo(DayWeather.Source.FORECAST);
        // 99.0 is the climate row for the same day; the forecast wins.
        assertThat(tomorrow.temperatureMax()).isEqualTo(18.2);

        var october = readings.get("cusco").get(LocalDate.parse("2026-10-24"));
        assertThat(october.source()).isEqualTo(DayWeather.Source.CLIMATE);
    }

    @Test
    void aNullConditionColumnLeavesTheCodeUnsetRatherThanZero() {
        CannedHttp climate = new CannedHttp().ok(CLIMATE_WITH_NULL_CODES);

        var reading = client(new CannedHttp(), climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"))
                .get("cusco").get(LocalDate.parse("2026-10-24"));

        // Zero would be a real WMO code meaning "clear sky", so reading a null
        // column as 0 would invent a sunny day out of no data at all.
        assertThat(reading.weatherCode()).isNull();
        assertThat(reading.precipitation()).isEqualTo(5.16);
    }

    @Test
    void aRateLimitedLookupIsNotRetriedAndYieldsNothing() {
        CannedHttp climate = new CannedHttp()
                .status(429, "{\"error\":true,\"reason\":\"Too many concurrent requests\"}");

        var readings = client(new CannedHttp(), climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"));

        // Asking again is the one response guaranteed to make a 429 worse.
        assertThat(climate.callCount()).isEqualTo(1);
        assertThat(readings.get("cusco")).isEmpty();
    }

    @Test
    void aServerErrorIsRetriedOnceAndTheSecondAnswerIsUsed() {
        CannedHttp climate = new CannedHttp()
                .status(500, "{\"error\":true,\"reason\":\"Something went wrong.\"}")
                .ok(ONE_LOCATION_CLIMATE);

        var readings = client(new CannedHttp(), climate)
                .lookUp(List.of(CUSCO), LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-24"));

        assertThat(climate.callCount()).isEqualTo(2);
        assertThat(readings.get("cusco")).hasSize(1);
    }



    @Test
    void nothingToLookUpMakesNoCallAtAll() {
        CannedHttp forecast = new CannedHttp();
        CannedHttp climate = new CannedHttp();
        WeatherClient client = client(forecast, climate);

        LocalDate day = LocalDate.parse("2026-10-24");
        assertThat(client.lookUp(List.of(), day, day)).isEmpty();
        assertThat(client.lookUp(List.of(CUSCO), null, day)).isEmpty();
        // An end before the start is not a range.
        assertThat(client.lookUp(List.of(CUSCO), day, day.minusDays(1))).isEmpty();

        assertThat(forecast.callCount()).isZero();
        assertThat(climate.callCount()).isZero();
    }





    @Test
    void theTtlIsTwelveHoursByDefault() {
        assertThat(props().weather().cacheTtl()).isEqualTo(java.time.Duration.ofHours(12));
    }
}
