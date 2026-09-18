package com.josephinealinea.planner.weather.infra;

import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.weather.domain.WeatherRecord;

/**
 * The storage contract for cached weather readings: {@link YamlWeatherRepository}
 * with the database flag off, a JDBC implementation with it on.
 *
 * Nothing beyond the per-trip basics: WeatherService does its own matching on
 * rounded coordinates and date. Ids ("lat,lon:date") repeat across trips by
 * design, which is why every store scopes them by trip.
 */
public interface WeatherRepository extends TripScopedRepository<WeatherRecord> {
}
