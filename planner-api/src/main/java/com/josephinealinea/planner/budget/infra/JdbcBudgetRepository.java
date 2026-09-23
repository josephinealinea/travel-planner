package com.josephinealinea.planner.budget.infra;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.domain.BudgetStatus;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
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
 * Budget rows in the {@code budget_items} table, with the database flag on.
 * List behaviour is {@link TripScopedJdbcRepository}'s; the finders are
 * {@link BudgetRepository}'s default methods, shared with YAML.
 *
 * Budget is where a mapping mistake costs money rather than tidiness, so:
 * <ul>
 *   <li><b>A row with no status is a charge.</b> A null {@code status} is
 *       written as CONFIRMED — the column's default, but an explicit NULL
 *       would not fall back to it — and a NULL read back is CONFIRMED too,
 *       the same answer {@code BudgetItem.setStatus(null)} and a YAML file
 *       without the key give (see BudgetStatus).</li>
 *   <li><b>{@code confirmedAt} is stored exactly as given.</b> It is stamped
 *       once by {@code markCharged} and never moved; the repository neither
 *       stamps nor clears it.</li>
 *   <li><b>{@code sharedByUserIds} is an ordered {@code text[]}.</b> Order
 *       decides who takes the leftover cent when a row does not divide
 *       evenly, so the list comes back in exactly the order it went in. Empty
 *       means the whole trip, and is stored as {@code '{}'}, never NULL.</li>
 *   <li><b>{@code amount} is {@code numeric}</b>, read with
 *       {@code getBigDecimal}: 0.1 stays 0.1, and the scale comes back as
 *       written.</li>
 *   <li><b>No sort field of its own.</b> Budget rows tie-break on insertion
 *       order, which is the table's {@code seq} — the order
 *       {@link #findAll} returns and {@link BudgetRepository#findAllOrdered}'s
 *       stable sort keeps.</li>
 *   <li>The legacy {@code destinationIds} key is not stored — see
 *       JdbcChecklistRepository.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcBudgetRepository extends TripScopedJdbcRepository<BudgetItem> implements BudgetRepository {

    public JdbcBudgetRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "budget_items",
                List.of("itinerary_item_id", "category", "description", "note", "amount", "currency", "date",
                        "country_codes", "shared_by_user_ids", "paid_by_user_id", "status", "confirmed_at",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id"),
                BudgetItem::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(BudgetItem item) {
        Map<String, Object> values = new HashMap<>();
        values.put("itinerary_item_id", item.getItineraryItemId());
        values.put("category", JdbcValues.enumName(
                item.getCategory() == null ? ChecklistCategory.OTHERS : item.getCategory()));
        values.put("description", item.getDescription());
        values.put("note", item.getNote());
        values.put("amount", item.getAmount());
        values.put("currency", item.getCurrency());
        values.put("date", item.getDate());
        values.put("country_codes", JdbcValues.textArray(item.getCountryCodes()));
        values.put("shared_by_user_ids", JdbcValues.textArray(item.getSharedByUserIds()));
        values.put("paid_by_user_id", item.getPaidByUserId());
        values.put("status", JdbcValues.enumName(
                item.getStatus() == null ? BudgetStatus.CONFIRMED : item.getStatus()));
        values.put("confirmed_at", JdbcValues.timestamptz(item.getConfirmedAt()));
        values.put("created_at", JdbcValues.timestamptz(item.getCreatedAt()));
        values.put("created_by_user_id", item.getCreatedByUserId());
        values.put("updated_at", JdbcValues.timestamptz(item.getUpdatedAt()));
        values.put("updated_by_user_id", item.getUpdatedByUserId());
        return values;
    }

    @Override
    protected BudgetItem mapRow(ResultSet rs) throws SQLException {
        BudgetItem item = new BudgetItem();
        item.setId(rs.getString("id"));
        item.setTripId(rs.getString("trip_id"));
        item.setItineraryItemId(rs.getString("itinerary_item_id"));
        item.setCategory(JdbcValues.enumValue(rs, "category", ChecklistCategory.class, ChecklistCategory.OTHERS));
        item.setDescription(rs.getString("description"));
        item.setNote(rs.getString("note"));
        item.setAmount(rs.getBigDecimal("amount"));
        item.setCurrency(rs.getString("currency"));
        item.setDate(JdbcValues.localDate(rs, "date"));
        item.setCountryCodes(JdbcValues.textList(rs, "country_codes"));
        item.setSharedByUserIds(JdbcValues.textList(rs, "shared_by_user_ids"));
        item.setPaidByUserId(rs.getString("paid_by_user_id"));
        item.setStatus(JdbcValues.enumValue(rs, "status", BudgetStatus.class, BudgetStatus.CONFIRMED));
        item.setConfirmedAt(JdbcValues.instant(rs, "confirmed_at"));
        item.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        item.setCreatedByUserId(rs.getString("created_by_user_id"));
        item.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        item.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        return item;
    }
}
