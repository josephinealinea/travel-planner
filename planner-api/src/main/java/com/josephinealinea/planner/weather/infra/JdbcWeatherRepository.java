package com.josephinealinea.planner.weather.infra;

import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import com.josephinealinea.planner.storage.jdbc.TripScopedJdbcRepository;
import com.josephinealinea.planner.weather.DayWeather;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cached weather readings in the {@code weather_records} table, with the
 * database flag on. Still a cache with a table behind it: every row was
 * written by a lookup and can be lost for the price of a repeat call.
 *
 * <ul>
 *   <li><b>Ids repeat across trips</b> — "lat,lon:date" names a point and a
 *       day, not a trip — which the {@code (trip_id, id)} key allows, so two
 *       trips through Cusco hold a record each and neither overwrites the
 *       other.</li>
 *   <li><b>A null reading stays null.</b> {@code weatherCode} 0 is a real WMO
 *       code meaning "clear sky", and the climate endpoint serves no code at
 *       all, so an absent code must never read back as 0 — hence
 *       {@link JdbcValues#nullableInteger}, and the same for the three
 *       measurements.</li>
 *   <li><b>{@code source} has no default.</b> A record whose source is
 *       unknown reads back as null, as in YAML, rather than being promoted to
 *       FORECAST or CLIMATE — that label is what stops a guess posing as a
 *       measurement.</li>
 *   <li><b>{@code latitude}, {@code longitude} and {@code date} are NOT
 *       NULL.</b> They are the record's key; WeatherService never writes a
 *       record without them. This is the one place a row YAML would accept
 *       (a record with no date) is refused here.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcWeatherRepository extends TripScopedJdbcRepository<WeatherRecord> implements WeatherRepository {

    public JdbcWeatherRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "weather_records",
                List.of("latitude", "longitude", "date", "weather_code",
                        "temperature_max", "temperature_min", "precipitation", "source", "fetched_at"),
                WeatherRecord::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(WeatherRecord record) {
        Map<String, Object> values = new HashMap<>();
        values.put("latitude", record.getLatitude());
        values.put("longitude", record.getLongitude());
        values.put("date", record.getDate());
        values.put("weather_code", record.getWeatherCode());
        values.put("temperature_max", record.getTemperatureMax());
        values.put("temperature_min", record.getTemperatureMin());
        values.put("precipitation", record.getPrecipitation());
        values.put("source", JdbcValues.enumName(record.getSource()));
        values.put("fetched_at", JdbcValues.timestamptz(record.getFetchedAt()));
        return values;
    }

    @Override
    protected WeatherRecord mapRow(ResultSet rs) throws SQLException {
        WeatherRecord record = new WeatherRecord();
        record.setId(rs.getString("id"));
        record.setLatitude(rs.getDouble("latitude"));
        record.setLongitude(rs.getDouble("longitude"));
        record.setDate(JdbcValues.localDate(rs, "date"));
        record.setWeatherCode(JdbcValues.nullableInteger(rs, "weather_code"));
        record.setTemperatureMax(JdbcValues.nullableDouble(rs, "temperature_max"));
        record.setTemperatureMin(JdbcValues.nullableDouble(rs, "temperature_min"));
        record.setPrecipitation(JdbcValues.nullableDouble(rs, "precipitation"));
        record.setSource(JdbcValues.enumValue(rs, "source", DayWeather.Source.class, null));
        record.setFetchedAt(JdbcValues.instant(rs, "fetched_at"));
        return record;
    }
}
