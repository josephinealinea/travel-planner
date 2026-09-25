package com.josephinealinea.planner.budget.infra;

import com.josephinealinea.planner.budget.domain.SettlementPayment;
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
 * Settlement payments in the {@code settlement_payments} table, with the
 * database flag on. List behaviour is {@link TripScopedJdbcRepository}'s.
 *
 * {@code amount} is {@code numeric}, read with {@code getBigDecimal}, so 0.1
 * stays 0.1 and the scale comes back as written — a payment is money that
 * settles to the cent. No sort field: insertion order is the table's
 * {@code seq}.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcSettlementPaymentRepository extends TripScopedJdbcRepository<SettlementPayment>
        implements SettlementPaymentRepository {

    public JdbcSettlementPaymentRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "settlement_payments",
                List.of("from_user_id", "to_user_id", "amount", "currency", "date", "note",
                        "created_at", "created_by_user_id", "updated_at", "updated_by_user_id"),
                SettlementPayment::getId);
    }

    @Override
    protected Map<String, Object> parametersOf(SettlementPayment payment) {
        Map<String, Object> values = new HashMap<>();
        values.put("from_user_id", payment.getFromUserId());
        values.put("to_user_id", payment.getToUserId());
        values.put("amount", payment.getAmount());
        values.put("currency", payment.getCurrency());
        values.put("date", payment.getDate());
        values.put("note", payment.getNote());
        values.put("created_at", JdbcValues.timestamptz(payment.getCreatedAt()));
        values.put("created_by_user_id", payment.getCreatedByUserId());
        values.put("updated_at", JdbcValues.timestamptz(payment.getUpdatedAt()));
        values.put("updated_by_user_id", payment.getUpdatedByUserId());
        return values;
    }

    @Override
    protected SettlementPayment mapRow(ResultSet rs) throws SQLException {
        SettlementPayment payment = new SettlementPayment();
        payment.setId(rs.getString("id"));
        payment.setTripId(rs.getString("trip_id"));
        payment.setFromUserId(rs.getString("from_user_id"));
        payment.setToUserId(rs.getString("to_user_id"));
        payment.setAmount(rs.getBigDecimal("amount"));
        payment.setCurrency(rs.getString("currency"));
        payment.setDate(JdbcValues.localDate(rs, "date"));
        payment.setNote(rs.getString("note"));
        payment.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        payment.setCreatedByUserId(rs.getString("created_by_user_id"));
        payment.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        payment.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        return payment;
    }
}
