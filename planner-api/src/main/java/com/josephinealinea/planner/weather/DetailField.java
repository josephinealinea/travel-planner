package com.josephinealinea.planner.weather;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Every extra daily value a weather card can show, in one place.
 *
 * This list drives what is asked of Open-Meteo, what is parsed back, and (by
 * WeatherDetailsDriftTest) what the published page's own copy of the request
 * must contain. Adding a field is one line here plus a chip in
 * js/weather.js and publish/page.js.
 *
 * {@code inClimate} is false for a variable the /climate endpoint accepts and
 * answers with a column of nulls — asked anyway it costs payload for nothing.
 * Verified live for a Cusco date; recheck if the model changes.
 *
 * {@code key} is what is stored and sent to the page. Absent means unknown:
 * a value of 0 is a real answer and is kept.
 */
public enum DetailField {
    SUNRISE("sunrise", "sunrise", true, true),
    SUNSET("sunset", "sunset", true, true),
    DAYLIGHT_SECONDS("daylightSeconds", "daylight_duration", false, true),
    UV_INDEX_MAX("uvIndexMax", "uv_index_max", false, false),
    PRECIPITATION_PROBABILITY_MAX("precipitationProbabilityMax", "precipitation_probability_max", false, false),
    RAIN_SUM("rainSum", "rain_sum", false, true),
    SNOWFALL_SUM("snowfallSum", "snowfall_sum", false, true),
    WIND_SPEED_MAX("windSpeedMax", "wind_speed_10m_max", false, true),
    WIND_GUSTS_MAX("windGustsMax", "wind_gusts_10m_max", false, true),
    WIND_DIRECTION("windDirection", "wind_direction_10m_dominant", false, false),
    APPARENT_TEMPERATURE_MAX("apparentTemperatureMax", "apparent_temperature_max", false, false),
    APPARENT_TEMPERATURE_MIN("apparentTemperatureMin", "apparent_temperature_min", false, false),
    HUMIDITY_MEAN("humidityMean", "relative_humidity_2m_mean", false, true),
    CLOUD_COVER_MEAN("cloudCoverMean", "cloud_cover_mean", false, true);

    private final String key;
    private final String parameter;
    private final boolean text;
    private final boolean inClimate;

    DetailField(String key, String parameter, boolean text, boolean inClimate) {
        this.key = key;
        this.parameter = parameter;
        this.text = text;
        this.inClimate = inClimate;
    }

    public String key() { return key; }
    public String parameter() { return parameter; }
    public boolean text() { return text; }
    public boolean inClimate() { return inClimate; }

    public static List<DetailField> forForecast() {
        return List.of(values());
    }

    public static List<DetailField> forClimate() {
        return Arrays.stream(values()).filter(DetailField::inClimate).toList();
    }

    public static String dailyParameter(List<DetailField> fields) {
        return fields.stream().map(DetailField::parameter).collect(Collectors.joining(","));
    }
}
