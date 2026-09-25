package com.josephinealinea.planner.weather.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.josephinealinea.planner.weather.DayWeather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * One day's weather for one point on the map, as it was last looked up.
 *
 * This is a cache with a file behind it, not a derived value — which is worth
 * being clear about, because CLAUDE.md says derived values are never stored.
 * The rule exists so nothing on disk can go quietly stale against the thing it
 * was computed from. A record here carries both halves of its own provenance:
 * the coordinates it was fetched for and the instant it was fetched. It can
 * always say whether it is still valid, which a stored nights count could not.
 *
 * Keyed by rounded coordinates rather than by destination id on purpose. Two
 * destinations at the same place share a record, renaming a destination does
 * not orphan its weather, and moving one to different coordinates correctly
 * stops matching — the old record simply ages out instead of silently
 * describing somewhere else.
 *
 * <b>A date in the past is never refetched.</b> The weather on 25 October 2026
 * stops changing once that day is over, so the record is frozen where it is and
 * no further call is ever made for it. What is frozen is whatever was last
 * held — a forecast or a climate projection, still labelled as such by
 * {@code source}, so a preserved guess never poses as a measurement.
 */
public class WeatherRecord {

    /** "lat,lon:date", so the file is one row per point per day. */
    private String id;
    private double latitude;
    private double longitude;
    private LocalDate date;

    private Integer weatherCode;
    private Double temperatureMax;
    private Double temperatureMin;
    private Double precipitation;
    private DayWeather.Source source;

    /**
     * The extra daily readings (sunrise, UV, wind, ...) as one document rather
     * than a column each, so the next field is a key and not a migration. Keys
     * are {@code DetailField.key()}; absent means unknown and 0 is a real
     * answer. Null on a record cached before this existed.
     */
    private Map<String, Object> details;

    /** When Open-Meteo answered. What the TTL is measured against. */
    private Instant fetchedAt;

    public WeatherRecord() {}

    /** Coordinates rounded to ~1km, which is finer than either endpoint's grid. */
    public static String keyFor(double latitude, double longitude, LocalDate date) {
        return "%.2f,%.2f:%s".formatted(latitude, longitude, date);
    }

    @JsonIgnore
    public boolean isForDateInThePast(LocalDate today) {
        return date != null && date.isBefore(today);
    }

    /**
     * Named for the question rather than with an is/get prefix Jackson would
     * bind to a property — see ItineraryItem.coversWholeDay in CLAUDE.md.
     */
    @JsonIgnore
    public boolean olderThan(Instant now, java.time.Duration ttl) {
        return fetchedAt == null || fetchedAt.plus(ttl).isBefore(now);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public Integer getWeatherCode() { return weatherCode; }
    public void setWeatherCode(Integer weatherCode) { this.weatherCode = weatherCode; }

    public Double getTemperatureMax() { return temperatureMax; }
    public void setTemperatureMax(Double temperatureMax) { this.temperatureMax = temperatureMax; }

    public Double getTemperatureMin() { return temperatureMin; }
    public void setTemperatureMin(Double temperatureMin) { this.temperatureMin = temperatureMin; }

    public Double getPrecipitation() { return precipitation; }
    public void setPrecipitation(Double precipitation) { this.precipitation = precipitation; }

    public DayWeather.Source getSource() { return source; }
    public void setSource(DayWeather.Source source) { this.source = source; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }
}
