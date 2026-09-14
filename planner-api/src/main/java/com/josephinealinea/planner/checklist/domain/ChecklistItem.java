package com.josephinealinea.planner.checklist.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import com.josephinealinea.planner.shared.Audited;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Free-text, member-editable. Three of these are seeded when a destination is
 * added, but nothing afterwards treats a seeded item differently — autoSeeded is
 * metadata only, and the description is the member's to rewrite.
 *
 * countryCodes can be empty: checklist items exist whether or not a
 * destination was ever added, and they survive any of their destinations being
 * deleted (see setLegacyDestinationId below for older, pre-multi-destination
 * records).
 */
public class ChecklistItem implements Audited {

    private String id;
    private String tripId;
    private List<String> countryCodes = new ArrayList<>();
    // Which destination the seeder made this from. Metadata, like autoSeeded:
    // the link a member picks is the country, but PlanTemplates needs the city
    // to suggest a name and its own check-in dates, and the Destinations table
    // counts per city. Never shown, never edited.
    private String seededFromDestinationId;
    private transient List<String> legacyDestinationIds = new ArrayList<>();
    private ChecklistCategory category = ChecklistCategory.OTHERS;
    private String description;
    private String note;
    private ChecklistStatus status = ChecklistStatus.TODO;
    private boolean autoSeeded;
    private int sortOrder;
    private Instant createdAt;
    // Who made this and who changed it last, for the question that only ever
    // gets asked after something looks wrong. Both are user ids — see Audited.
    // The update pair stays absent until there is an actual edit to record.
    private String createdByUserId;
    private Instant updatedAt;
    private String updatedByUserId;
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

    public String getSeededFromDestinationId() { return seededFromDestinationId; }
    public void setSeededFromDestinationId(String id) { this.seededFromDestinationId = id; }

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

    /**
     * The oldest shape of all: a single `destinationId`, from before the link
     * was even a list. Folded into the same legacy holder as the plural key so
     * the migration sees one thing to map.
     */
    @JsonProperty("destinationId")
    private void setLegacyDestinationId(String legacyDestinationId) {
        if (legacyDestinationId != null && !legacyDestinationId.isBlank()
                && !legacyDestinationIds.contains(legacyDestinationId)) {
            legacyDestinationIds.add(legacyDestinationId);
        }
    }

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

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
