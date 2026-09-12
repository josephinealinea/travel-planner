package com.josephinealinea.planner.budget.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An expense. Created automatically when a plan is given a cost, or added by
 * hand. itineraryItemId records which plan produced it; a manual expense has
 * none.
 *
 * Once created, the description, category and date belong to whoever edits the
 * budget — later itinerary edits only ever touch amount and currency.
 */
public class BudgetItem {

    private String id;
    private String tripId;
    private String itineraryItemId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private BigDecimal amount;
    private String currency;
    private LocalDate date;
    private Instant createdAt;

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

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
