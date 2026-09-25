package com.josephinealinea.planner.budget.domain;

import com.josephinealinea.planner.shared.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Money one trip member handed another to settle what they owed.
 *
 * <b>Deliberately not a {@link BudgetItem}.</b> A payment is not spending: it
 * moves money between two people who already spent it, so it must change no
 * total, slice or forecast. Storing it as an expense with a flag would leave
 * every reader of expenses — the table, both breakdowns, native totals, the
 * other tabs, the publisher, the importer's verifier — to remember to skip it.
 * As a record of its own it is excluded from all of them by construction, and
 * the only code that reads it is the settlement maths in BudgetService.
 *
 * To the ledger it is exactly a row "paid by {@code fromUserId}, shared by
 * {@code toUserId}": the payer is credited the amount and the receiver debited
 * it, in {@code currency}, never converted.
 *
 * Immutable in practice: there is no edit, only delete and record again. The
 * {@code updated…} audit fields therefore stay absent, which is what "nobody
 * has touched this since it was made" looks like.
 */
public class SettlementPayment implements Audited {

    private String id;
    private String tripId;
    /** Who handed the money over — a trip-member user id. */
    private String fromUserId;
    /** Who received it. */
    private String toUserId;
    private BigDecimal amount;
    private String currency;
    private LocalDate date;
    private String note;
    private Instant createdAt;
    private String createdByUserId;
    private Instant updatedAt;
    private String updatedByUserId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    @Override public Instant getCreatedAt() { return createdAt; }
    @Override public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override public String getCreatedByUserId() { return createdByUserId; }
    @Override public void setCreatedByUserId(String userId) { this.createdByUserId = userId; }

    @Override public Instant getUpdatedAt() { return updatedAt; }
    @Override public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override public String getUpdatedByUserId() { return updatedByUserId; }
    @Override public void setUpdatedByUserId(String userId) { this.updatedByUserId = userId; }
}
