package com.josephinealinea.planner.checklist.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Free-text, member-editable. Three of these are seeded when a destination is
 * added, but nothing afterwards treats a seeded item differently — autoSeeded is
 * metadata only, and the description is the member's to rewrite.
 *
 * destinationId is nullable: checklist items exist whether or not a destination
 * was ever added, and they survive the destination being deleted.
 */
public class ChecklistItem {

    private String id;
    private String tripId;
    private String destinationId;
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private String note;
    private ChecklistStatus status = ChecklistStatus.TODO;
    private boolean autoSeeded;
    private int sortOrder;
    private Instant createdAt;
    private Instant completedAt;

    public ChecklistItem() {}

    @JsonIgnore
    public boolean isCompleted() {
        return status == ChecklistStatus.COMPLETED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getDestinationId() { return destinationId; }
    public void setDestinationId(String destinationId) { this.destinationId = destinationId; }

    public ChecklistCategory getCategory() { return category; }
    public void setCategory(ChecklistCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public ChecklistStatus getStatus() { return status; }
    public void setStatus(ChecklistStatus status) { this.status = status; }

    public boolean isAutoSeeded() { return autoSeeded; }
    public void setAutoSeeded(boolean autoSeeded) { this.autoSeeded = autoSeeded; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
