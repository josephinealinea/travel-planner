package com.josephinealinea.planner.budget.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;

import com.josephinealinea.planner.shared.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * An expense. Created automatically when a plan is given a cost, or added by
 * hand. itineraryItemId records which plan produced it; a manual expense has
 * none.
 *
 * Once created, the description, category, date and countryCodes belong to
 * whoever edits the budget — later itinerary edits only ever touch amount and
 * currency (see BudgetSync). countryCodes can be empty: a row exists whether
 * or not it names a place, and it survives any of its destinations being
 * deleted (see DestinationService.delete, which unlinks it the same way it
 * unlinks a checklist item or itinerary entry).
 *
 * `status` says whether the money has actually left yet, and is the one field
 * here that changes what the rollup above the table counts — see BudgetStatus
 * and markCharged. `sharedByUserIds` says whose money it was, which decides
 * both whose Budget tab the row appears on and how much of it counts there.
 */
public class BudgetItem implements Audited {

    private String id;
    private String tripId;
    private String itineraryItemId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private String note;
    private BigDecimal amount;
    private String currency;
    private LocalDate date;
    private List<String> countryCodes = new ArrayList<>();
    private transient List<String> legacyDestinationIds = new ArrayList<>();

    /**
     * Who this expense is shared by, as trip-member user ids — what the forms
     * label "Shared by".
     *
     * User ids rather than names or emails, for the reason the audit fields
     * give: both of those change, and the member list already resolves a screen
     * name per request, so a stored copy would be a stale answer to a question
     * the user record answers correctly.
     *
     * <b>Empty means the whole trip</b>, and nothing resolves that here — see
     * TripMembers, which owns both that rule and the division. Each sharer's
     * part is computed per request and never stored: it depends on how many
     * people share the row, so a stored copy would go quietly wrong the moment
     * one was added.
     */
    private List<String> sharedByUserIds = new ArrayList<>();

    /**
     * Who put the money down — what the forms label "Paid by". One trip-member
     * user id, or nobody, which is every row written before this existed.
     *
     * It is never counted in any figure. Paying for the table's dinner does
     * not make it your dinner: whose money a row is, and how much of it counts
     * on each Budget tab, stays decided by sharedByUserIds alone. A plain field
     * with a plain getter and setter and no derived accessor beside it, for the
     * Jackson reasons in CLAUDE.md, Traps.
     */
    private String paidByUserId;

    /**
     * Charged already, or still to come. CONFIRMED by default so a record
     * written before this field existed reads as the charge it was — see
     * BudgetStatus.
     */
    private BudgetStatus status = BudgetStatus.CONFIRMED;

    /**
     * When it was marked charged. Kept rather than derived: a row can be
     * confirmed long after it was created and long after its own date, and
     * updatedAt already means something else (any edit at all).
     */
    private Instant confirmedAt;

    private Instant createdAt;
    // Who made this and who changed it last, for the question that only ever
    // gets asked after something looks wrong. Both are user ids — see Audited.
    // The update pair stays absent until there is an actual edit to record.
    private String createdByUserId;
    private Instant updatedAt;
    private String updatedByUserId;

    public BudgetItem() {}

    @JsonIgnore
    public boolean isFromPlan() {
        return itineraryItemId != null;
    }

    @JsonIgnore
    public boolean isConfirmed() {
        return status != BudgetStatus.PENDING;
    }

    /**
     * The one place the charged/pending transition is written, because both
     * doors into it — the budget's own form and a plan's cost (see BudgetSync)
     * — have to agree about what the two states mean.
     *
     * Named for the action rather than with a set prefix Jackson would bind to
     * a property of its own: this writes two fields, and a phantom `charged`
     * key in the YAML that deserialised into neither of them would be a lie on
     * disk. See ItineraryItem.coversWholeDay in CLAUDE.md, Traps.
     *
     * Confirming stamps {@code confirmedAt} only when there is nothing there
     * already, so editing the description of a charge made in March does not
     * restamp it as September. Un-confirming clears it outright: a pending row
     * carrying a confirmation date would be a row claiming to have been paid.
     */
    public void markCharged(boolean charged, Instant now) {
        if (!charged) {
            this.status = BudgetStatus.PENDING;
            this.confirmedAt = null;
            return;
        }
        this.status = BudgetStatus.CONFIRMED;
        if (this.confirmedAt == null) this.confirmedAt = now;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getItineraryItemId() { return itineraryItemId; }
    public void setItineraryItemId(String itineraryItemId) { this.itineraryItemId = itineraryItemId; }

    public ChecklistCategory getCategory() { return category; }
    public void setCategory(ChecklistCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public List<String> getCountryCodes() { return countryCodes; }
    public void setCountryCodes(List<String> countryCodes) {
        this.countryCodes = countryCodes == null ? new ArrayList<>() : new ArrayList<>(countryCodes);
    }

    public List<String> getSharedByUserIds() { return sharedByUserIds; }
    public void setSharedByUserIds(List<String> sharedByUserIds) {
        this.sharedByUserIds = sharedByUserIds == null
                ? new ArrayList<>() : new ArrayList<>(sharedByUserIds);
    }

    public String getPaidByUserId() { return paidByUserId; }
    public void setPaidByUserId(String paidByUserId) { this.paidByUserId = paidByUserId; }

    /**
     * Every record written before this became a country link carried
     * `destinationIds`. YamlStore ignores unknown properties, so without this
     * setter an old record would silently lose its link the moment it was read
     * — and a destination id is not a country code, so it cannot simply be
     * folded in the way ChecklistItem's legacy single id was. It is kept
     * verbatim for the migration to map, and never written back: the next save
     * produces `countryCodes` only.
     */
    @JsonProperty("destinationIds")
    private void setLegacyDestinationIds(List<String> legacy) {
        this.legacyDestinationIds = legacy == null ? new ArrayList<>() : new ArrayList<>(legacy);
    }

    @JsonIgnore
    public List<String> legacyDestinationIds() { return legacyDestinationIds; }

    public BudgetStatus getStatus() { return status; }
    /** A record whose YAML predates this field has no status; that is a charge. */
    public void setStatus(BudgetStatus status) {
        this.status = status == null ? BudgetStatus.CONFIRMED : status;
    }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
