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
 */
public class BudgetItem implements Audited {

    private String id;
    private String tripId;
    private String itineraryItemId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private BigDecimal amount;
    private String currency;
    private LocalDate date;
    private List<String> countryCodes = new ArrayList<>();
    private transient List<String> legacyDestinationIds = new ArrayList<>();
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

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
