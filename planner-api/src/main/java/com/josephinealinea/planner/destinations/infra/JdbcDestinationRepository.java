package com.josephinealinea.planner.destinations.infra;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import com.josephinealinea.planner.storage.jdbc.TripScopedJdbcRepository;
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
 * Destinations in the {@code destinations} table, with the database flag on.
 * Everything list-shaped — order, upsert in place, replaceAll — is
 * {@link TripScopedJdbcRepository}'s; route order is
 * {@link DestinationRepository#findAllOrdered}'s, shared with YAML.
 *
 * Two things in the mapping carry meaning:
 * <ul>
 *   <li><b>{@code lodgingSeeded} and {@code suppressChecklist} stay
 *       three-valued.</b> Absent is the state of every destination that never
 *       produced an accommodation item or never had its checklist suppressed,
 *       and it is what YAML writes by leaving the key out — so they are read
 *       with {@link JdbcValues#nullableBoolean}, never {@code getBoolean}.</li>
 *   <li><b>Nights and days are not columns.</b> Both are counted from the two
 *       dates on every read, here exactly as in a YAML file.</li>
 * </ul>
 *
 * {@code tripId} comes back from the row's {@code trip_id}, which is the trip
 * the slug named — never whatever the entity carried when it was saved.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcDestinationRepository extends TripScopedJdbcRepository<Destination> implements DestinationRepository {

    public JdbcDestinationRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "destinations",
                List.of("name", "country_code", "country_name", "country_flag",
                        "latitude", "longitude", "geoname_id", "timezone",
                        "start_date", "end_date", "notes", "sort_order",
                        "lodging_seeded", "suppress_checklist", "traveller_ids",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id"),
                Destination::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(Destination d) {
        Map<String, Object> values = new HashMap<>();
        values.put("name", d.getName());
        values.put("country_code", d.getCountryCode());
        values.put("country_name", d.getCountryName());
        values.put("country_flag", d.getCountryFlag());
        values.put("latitude", d.getLatitude());
        values.put("longitude", d.getLongitude());
        values.put("geoname_id", d.getGeonameId());
        values.put("timezone", d.getTimezone());
        values.put("start_date", d.getStartDate());
        values.put("end_date", d.getEndDate());
        values.put("notes", d.getNotes());
        values.put("sort_order", d.getSortOrder());
        values.put("lodging_seeded", d.getLodgingSeeded());
        values.put("suppress_checklist", d.getSuppressChecklist());
        values.put("traveller_ids", JdbcValues.nullableTextArray(d.getTravellerIds()));
        values.put("created_at", JdbcValues.timestamptz(d.getCreatedAt()));
        values.put("created_by_user_id", d.getCreatedByUserId());
        values.put("updated_at", JdbcValues.timestamptz(d.getUpdatedAt()));
        values.put("updated_by_user_id", d.getUpdatedByUserId());
        return values;
    }

    @Override
    protected Destination mapRow(ResultSet rs) throws SQLException {
        Destination d = new Destination();
        d.setId(rs.getString("id"));
        d.setTripId(rs.getString("trip_id"));
        d.setName(rs.getString("name"));
        d.setCountryCode(rs.getString("country_code"));
        d.setCountryName(rs.getString("country_name"));
        d.setCountryFlag(rs.getString("country_flag"));
        d.setLatitude(JdbcValues.nullableDouble(rs, "latitude"));
        d.setLongitude(JdbcValues.nullableDouble(rs, "longitude"));
        d.setGeonameId(JdbcValues.nullableLong(rs, "geoname_id"));
        d.setTimezone(rs.getString("timezone"));
        d.setStartDate(JdbcValues.localDate(rs, "start_date"));
        d.setEndDate(JdbcValues.localDate(rs, "end_date"));
        d.setNotes(rs.getString("notes"));
        d.setSortOrder(rs.getInt("sort_order"));
        d.setLodgingSeeded(JdbcValues.nullableBoolean(rs, "lodging_seeded"));
        d.setSuppressChecklist(JdbcValues.nullableBoolean(rs, "suppress_checklist"));
        // NULL and '{}' are different states: see Travellers.
        d.setTravellerIds(JdbcValues.nullableTextList(rs, "traveller_ids"));
        d.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        d.setCreatedByUserId(rs.getString("created_by_user_id"));
        d.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        d.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        return d;
    }
}
