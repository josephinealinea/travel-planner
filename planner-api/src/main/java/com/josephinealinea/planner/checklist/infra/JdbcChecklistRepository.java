package com.josephinealinea.planner.checklist.infra;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
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
 * Checklist items in the {@code checklist_items} table, with the database flag
 * on. List behaviour is {@link TripScopedJdbcRepository}'s; "to do first, then
 * completed" is {@link ChecklistRepository#findAllOrdered}'s, shared with YAML.
 *
 * <ul>
 *   <li><b>{@code category} and {@code status} are {@code NOT NULL DEFAULT}
 *       columns, and an explicit null is not "use the default"</b> — so a
 *       null field is written as the domain's own initialiser (OTHERS, TODO),
 *       the same answer a YAML file with the key absent reads back as.</li>
 *   <li><b>{@code countryCodes} is an ordered {@code text[]}</b>: the order a
 *       member picked the countries in is the order the chips show.</li>
 *   <li><b>{@code seededFromDestinationId} is metadata, not a link</b> — no
 *       foreign key, because it deliberately outlives the destination it
 *       names (see "Deleting a destination unlinks" in CLAUDE.md).</li>
 *   <li><b>The legacy {@code destinationId(s)} keys are not stored.</b> They
 *       exist only so an old YAML file loads; the one-off import runs them
 *       through the same upconversion before anything reaches a table, so
 *       {@code legacyDestinationIds()} is always empty here.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcChecklistRepository extends TripScopedJdbcRepository<ChecklistItem> implements ChecklistRepository {

    public JdbcChecklistRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "checklist_items",
                List.of("country_codes", "seeded_from_destination_id", "category", "description", "note",
                        "status", "auto_seeded", "sort_order", "completed_at",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id", "traveller_ids"),
                ChecklistItem::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(ChecklistItem item) {
        Map<String, Object> values = new HashMap<>();
        values.put("country_codes", JdbcValues.textArray(item.getCountryCodes()));
        values.put("seeded_from_destination_id", item.getSeededFromDestinationId());
        values.put("category", JdbcValues.enumName(
                item.getCategory() == null ? ChecklistCategory.OTHERS : item.getCategory()));
        values.put("description", item.getDescription());
        values.put("note", item.getNote());
        values.put("status", JdbcValues.enumName(
                item.getStatus() == null ? ChecklistStatus.TODO : item.getStatus()));
        values.put("auto_seeded", item.isAutoSeeded());
        values.put("sort_order", item.getSortOrder());
        values.put("completed_at", JdbcValues.timestamptz(item.getCompletedAt()));
        values.put("created_at", JdbcValues.timestamptz(item.getCreatedAt()));
        values.put("created_by_user_id", item.getCreatedByUserId());
        values.put("updated_at", JdbcValues.timestamptz(item.getUpdatedAt()));
        values.put("updated_by_user_id", item.getUpdatedByUserId());
        values.put("traveller_ids", JdbcValues.nullableTextArray(item.getTravellerIds()));
        return values;
    }

    @Override
    protected ChecklistItem mapRow(ResultSet rs) throws SQLException {
        ChecklistItem item = new ChecklistItem();
        item.setId(rs.getString("id"));
        item.setTripId(rs.getString("trip_id"));
        item.setCountryCodes(JdbcValues.textList(rs, "country_codes"));
        item.setSeededFromDestinationId(rs.getString("seeded_from_destination_id"));
        item.setCategory(JdbcValues.enumValue(rs, "category", ChecklistCategory.class, ChecklistCategory.OTHERS));
        item.setDescription(rs.getString("description"));
        item.setNote(rs.getString("note"));
        item.setStatus(JdbcValues.enumValue(rs, "status", ChecklistStatus.class, ChecklistStatus.TODO));
        item.setAutoSeeded(rs.getBoolean("auto_seeded"));
        item.setSortOrder(rs.getInt("sort_order"));
        item.setCompletedAt(JdbcValues.instant(rs, "completed_at"));
        item.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        item.setCreatedByUserId(rs.getString("created_by_user_id"));
        item.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        item.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        // NULL and '{}' are different states: see Travellers.
        item.setTravellerIds(JdbcValues.nullableTextList(rs, "traveller_ids"));
        return item;
    }
}
