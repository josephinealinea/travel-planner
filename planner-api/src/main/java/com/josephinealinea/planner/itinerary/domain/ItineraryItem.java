package com.josephinealinea.planner.itinerary.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;

import com.josephinealinea.planner.shared.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A concrete plan — "Delta flight (DL 6071) from LAX to LIM". Usually created
 * from a checklist item's Plan button, but checklistItemId is nullable so an
 * entry can also be added straight to the itinerary.
 *
 * budgetItemId is the back-link to the budget row that the cost created, which
 * is what lets a later cost edit follow through and a cost removal clean up.
 *
 * A stay is stored as one entry per day it covers, written at create time, so
 * each night is an ordinary item that can be selected, edited and deleted on
 * its own. Only the check-in entry carries the cost, so one booking still
 * produces one budget row, and only it carries no planId — the later days
 * point back at it, which is what keeps "one plan" and "five days" separate
 * everywhere they are counted. The nights between carry allDay.
 *
 * countryCodes can be empty: an itinerary entry exists whether or not it is
 * tied to a place, and it survives any of its destinations being deleted (see
 * DestinationService.delete, which unlinks it the same way it unlinks a
 * checklist item).
 */
public class ItineraryItem implements Audited {

    private String id;
    private String tripId;
    private String checklistItemId;
    // Which plan this row belongs to. Absent on the plan's own row — that row
    // *is* the plan — and set to that row's id on each further day it covers.
    // Deliberately not "stayId": only lodging spans days today, but the
    // grouping says nothing about category, and a multi-day train filed under
    // "stay" would be a lie in the schema.
    private String planId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    // A night in the middle of a stay: it belongs to a day but not to a time.
    // Boxed and NON_NULL so it is simply absent from the YAML of every ordinary
    // entry rather than writing "allDay: false" on all of them.
    private Boolean allDay;
    private BigDecimal cost;
    private String currency;
    private String budgetItemId;
    private List<String> countryCodes = new ArrayList<>();
    private transient List<String> legacyDestinationIds = new ArrayList<>();
    private int sortOrder;
    private Instant createdAt;
    // Who made this and who changed it last, for the question that only ever
    // gets asked after something looks wrong. Both are user ids — see Audited.
    // The update pair stays absent until there is an actual edit to record.
    private String createdByUserId;
    private Instant updatedAt;
    private String updatedByUserId;

    public ItineraryItem() {}

    @JsonIgnore
    public boolean hasCost() {
        return cost != null && cost.signum() != 0;
    }

    public Boolean getAllDay() { return allDay; }
    public void setAllDay(Boolean allDay) { this.allDay = allDay; }

    /**
     * Deliberately not named isAllDay/getAllDay. Jackson treats every
     * get/is accessor whose name resolves to "allDay" as the same property,
     * and a @JsonIgnore on any one of them drops the whole property — the
     * field then never reaches the YAML and a stay's nights come back timed.
     */
    public boolean coversWholeDay() { return Boolean.TRUE.equals(allDay); }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getChecklistItemId() { return checklistItemId; }
    public void setChecklistItemId(String checklistItemId) { this.checklistItemId = checklistItemId; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    /**
     * True for the row that is the plan itself rather than one of its later
     * days. Named for the question it answers, not with an is/get prefix that
     * Jackson would bind to a property — see coversWholeDay().
     */
    public boolean ownsItsPlan() { return planId == null; }

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

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
