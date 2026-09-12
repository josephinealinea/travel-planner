package com.josephinealinea.planner.itinerary.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * A concrete plan — "Delta flight (DL 6071) from LAX to LIM". Usually created
 * from a checklist item's Plan button, but checklistItemId is nullable so an
 * entry can also be added straight to the itinerary.
 *
 * budgetItemId is the back-link to the budget row that the cost created, which
 * is what lets a later cost edit follow through and a cost removal clean up.
 */
public class ItineraryItem {

    private String id;
    private String tripId;
    private String checklistItemId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private BigDecimal cost;
    private String currency;
    private String budgetItemId;
    private int sortOrder;
    private Instant createdAt;

    public ItineraryItem() {}

    @JsonIgnore
    public boolean hasCost() {
        return cost != null && cost.signum() != 0;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getChecklistItemId() { return checklistItemId; }
    public void setChecklistItemId(String checklistItemId) { this.checklistItemId = checklistItemId; }

    public ChecklistCategory getCategory() { return category; }
    public void setCategory(ChecklistCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }

    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }

    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getBudgetItemId() { return budgetItemId; }
    public void setBudgetItemId(String budgetItemId) { this.budgetItemId = budgetItemId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
