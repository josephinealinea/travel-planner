package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.storage.EveryField;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a {@link SettlementPaymentRepository} must do on top of the
 * generic per-trip contract, run against YAML and PostgreSQL alike.
 *
 * A payment is money settled to the cent, so the round trip is the test that
 * matters: every field back exactly as saved — the amount's scale included —
 * and none of them left at a default the fixture could hide behind.
 */
public abstract class SettlementPaymentRepositoryContract
        extends TripScopedRepositoryContract<SettlementPayment> {

    protected abstract SettlementPaymentRepository store();

    @Override
    protected TripScopedRepository<SettlementPayment> repository() {
        return store();
    }

    protected static String tripIdOf(String slug) {
        return "trip-" + slug;
    }

    @Override
    protected SettlementPayment entity(String id, String label) {
        SettlementPayment payment = new SettlementPayment();
        payment.setId(id);
        payment.setTripId(tripIdOf(TRIP));
        payment.setNote(label);
        return payment;
    }

    @Override
    protected String idOf(SettlementPayment entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(SettlementPayment entity) {
        return entity.getNote();
    }

    @Test
    void everyFieldComesBackAsItWasSaved() {
        SettlementPayment full = fullyPopulated();
        EveryField.assertEverySet(full, SettlementPayment::new, Set.of());

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .isEqualTo(full);
    }

    @Test
    void aPaymentWithNothingButItsIdComesBackThatWay() {
        SettlementPayment bare = entity("bare", null);
        store().save(TRIP, bare);

        assertThat(store().findById(TRIP, "bare")).get()
                .usingRecursiveComparison().isEqualTo(bare);
    }

    static SettlementPayment fullyPopulated() {
        SettlementPayment payment = new SettlementPayment();
        payment.setId("pay-sam-to-alex");
        payment.setTripId(tripIdOf(TRIP));
        payment.setFromUserId("user-sam");
        payment.setToUserId("user-alex");
        payment.setAmount(new BigDecimal("246.22"));
        payment.setCurrency("PEN");
        payment.setDate(LocalDate.of(2026, 10, 25));
        payment.setNote("Cash at the hostel");
        payment.setCreatedAt(Instant.parse("2026-09-01T10:15:30.123456Z"));
        payment.setCreatedByUserId("user-sam");
        payment.setUpdatedAt(Instant.parse("2026-09-02T08:00:00Z"));
        payment.setUpdatedByUserId("user-alex");
        return payment;
    }
}
