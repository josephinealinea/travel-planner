package com.josephinealinea.planner.itinerary.infra;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
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
 * Itinerary entries in the {@code itinerary_items} table, with the database
 * flag on. List behaviour is {@link TripScopedJdbcRepository}'s; chronological
 * order and {@code findByChecklistItem} are {@link ItineraryRepository}'s,
 * shared with YAML.
 *
 * <ul>
 *   <li><b>{@code startAt}/{@code endAt} are wall-clock, so the columns are
 *       {@code timestamp} without time zone</b> and bound as
 *       {@code LocalDateTime}. A 06:00 departure from Lima is 06:00 whatever
 *       zone the server, the session or the reader is in; a
 *       {@code timestamptz} would have shifted it.</li>
 *   <li><b>{@code allDay} stays three-valued.</b> Null is "never said", which
 *       is how every entry written before the flag existed reads, and the
 *       itinerary's PATCH distinguishes null from false. Read with
 *       {@link JdbcValues#nullableBoolean}.</li>
 *   <li><b>{@code cost} is {@code numeric}</b> and read with
 *       {@code getBigDecimal}, so 246.22 comes back as 246.22 — scale
 *       included — and never passes through a double.</li>
 *   <li><b>{@code planId} and {@code budgetItemId} carry no foreign key.</b> A
 *       plan's days and its budget row are linked by id only, exactly as in
 *       YAML; {@code adoptOrphanedDays} and BudgetSync keep them honest.</li>
 *   <li>The legacy {@code destinationIds} key is not stored — see
 *       JdbcChecklistRepository.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcItineraryRepository extends TripScopedJdbcRepository<ItineraryItem> implements ItineraryRepository {

    public JdbcItineraryRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "itinerary_items",
                List.of("checklist_item_id", "plan_id", "category", "description",
                        "start_at", "end_at", "all_day", "cost", "currency", "budget_item_id",
                        "country_codes", "sort_order",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id", "traveller_ids"),
                ItineraryItem::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(ItineraryItem item) {
        Map<String, Object> values = new HashMap<>();
        values.put("checklist_item_id", item.getChecklistItemId());
        values.put("plan_id", item.getPlanId());
        values.put("category", JdbcValues.enumName(
                item.getCategory() == null ? ChecklistCategory.OTHERS : item.getCategory()));
        values.put("description", item.getDescription());
        values.put("start_at", item.getStartAt());
        values.put("end_at", item.getEndAt());
        values.put("all_day", item.getAllDay());
        values.put("cost", item.getCost());
        values.put("currency", item.getCurrency());
        values.put("budget_item_id", item.getBudgetItemId());
        values.put("country_codes", JdbcValues.textArray(item.getCountryCodes()));
        values.put("sort_order", item.getSortOrder());
        values.put("created_at", JdbcValues.timestamptz(item.getCreatedAt()));
        values.put("created_by_user_id", item.getCreatedByUserId());
        values.put("updated_at", JdbcValues.timestamptz(item.getUpdatedAt()));
        values.put("updated_by_user_id", item.getUpdatedByUserId());
        values.put("traveller_ids", JdbcValues.nullableTextArray(item.getTravellerIds()));
        return values;
    }

    @Override
    protected ItineraryItem mapRow(ResultSet rs) throws SQLException {
        ItineraryItem item = new ItineraryItem();
        item.setId(rs.getString("id"));
        item.setTripId(rs.getString("trip_id"));
        item.setChecklistItemId(rs.getString("checklist_item_id"));
        item.setPlanId(rs.getString("plan_id"));
        item.setCategory(JdbcValues.enumValue(rs, "category", ChecklistCategory.class, ChecklistCategory.OTHERS));
        item.setDescription(rs.getString("description"));
        item.setStartAt(JdbcValues.localDateTime(rs, "start_at"));
        item.setEndAt(JdbcValues.localDateTime(rs, "end_at"));
        item.setAllDay(JdbcValues.nullableBoolean(rs, "all_day"));
        item.setCost(rs.getBigDecimal("cost"));
        item.setCurrency(rs.getString("currency"));
        item.setBudgetItemId(rs.getString("budget_item_id"));
        item.setCountryCodes(JdbcValues.textList(rs, "country_codes"));
        item.setSortOrder(rs.getInt("sort_order"));
        item.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        item.setCreatedByUserId(rs.getString("created_by_user_id"));
        item.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        item.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        // NULL and '{}' are different states: see Travellers.
        item.setTravellerIds(JdbcValues.nullableTextList(rs, "traveller_ids"));
        return item;
    }
}
