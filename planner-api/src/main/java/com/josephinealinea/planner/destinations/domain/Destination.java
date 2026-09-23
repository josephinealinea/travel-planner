package com.josephinealinea.planner.destinations.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.shared.Nights;

import com.josephinealinea.planner.shared.Audited;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A place on the trip, captured as free text. Coordinates come from the
 * countries.dev lookup when a suggestion is picked, but every field stays
 * editable — some real destinations have no gazetteer entry under the name
 * travellers use for them, so manual entry is a first-class path, not a
 * fallback.
 *
 * Both dates are optional. When both are present the nights count feeds the
 * seeded accommodation checklist wording.
 */
public class Destination implements Audited {

    private String id;
    private String tripId;
    private String name;
    private String countryCode;
    private String countryName;
    private String countryFlag;
    private Double latitude;
    private Double longitude;
    private Long geonameId;
    private String timezone;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;
    private int sortOrder;
    private Instant createdAt;
    // Who made this and who changed it last, for the question that only ever
    // gets asked after something looks wrong. Both are user ids — see Audited.
    // The update pair stays absent until there is an actual edit to record.
    private String createdByUserId;
    private Instant updatedAt;
    private String updatedByUserId;
    // Whether this destination has ever produced an accommodation checklist
    // item. Dates can arrive after the destination does, so that item is
    // sometimes seeded on a later edit — and this is what stops it being
    // seeded twice, or coming back after somebody deleted it on purpose.
    // Boxed and NON_NULL so it is simply absent until it is true.
    private Boolean lodgingSeeded;

    /**
     * Whether this destination seeds checklist items at all.
     *
     * Stored rather than treated as a one-off form choice, because the
     * accommodation item is seeded *later* too — when dates arrive that cover
     * a night (see DestinationService.seedLodgingIfTheDatesNowNeedIt). Without
     * remembering the answer, suppressing the checklist and then filling in
     * the dates would quietly produce the one item the member said they did
     * not want.
     *
     * Boxed and NON_NULL so it is simply absent until somebody asks for it.
     */
    private Boolean suppressChecklist;

    /**
     * Which travel buddies this is for. Three states, and the first two are
     * not the same: null is "not set, same as the parent" (for a destination
     * the parent is the whole trip); an empty list is explicitly the whole
     * trip; otherwise those member user ids, in the order picked. Resolved by
     * trips.api.Travellers; never read directly to decide who sees what.
     */
    private List<String> travellerIds;

    public Destination() {}

    public List<String> getTravellerIds() { return travellerIds; }
    public void setTravellerIds(List<String> travellerIds) {
        // Null stays null: it is a state ("follow the parent"), not an empty list.
        this.travellerIds = travellerIds == null ? null : new ArrayList<>(travellerIds);
    }

    public Boolean getLodgingSeeded() { return lodgingSeeded; }
    public void setLodgingSeeded(Boolean lodgingSeeded) { this.lodgingSeeded = lodgingSeeded; }

    /**
     * Named for the question rather than with a get/is prefix that Jackson
     * would bind to the same property — see ItineraryItem.coversWholeDay.
     */
    public boolean hasSeededLodging() { return Boolean.TRUE.equals(lodgingSeeded); }

    public Boolean getSuppressChecklist() { return suppressChecklist; }
    public void setSuppressChecklist(Boolean suppressChecklist) {
        this.suppressChecklist = suppressChecklist;
    }

    /** Named for the question, not as a get/is accessor — see coversWholeDay. */
    public boolean seedsNoChecklist() { return Boolean.TRUE.equals(suppressChecklist); }

    public Long nights() {
        return Nights.between(startDate, endDate);
    }

    /**
     * Days visited, counting both ends — 25-Oct to 31-Oct is 7, a day trip is 1.
     *
     * Deliberately not `nights() + 1`: nights is null for a day trip on
     * purpose (that null is the accommodation test), so adding one would make
     * the trip's only single-day stop count as nothing. See "Nights versus
     * days" in CLAUDE.md, and daysBetween in js/format.js which mirrors this.
     */
    public Long days() {
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) return null;
        return java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    @JsonIgnore
    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTripId() { return tripId; }
    public void setTripId(String tripId) { this.tripId = tripId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCountryCode() { return countryCode; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }

    public String getCountryName() { return countryName; }
    public void setCountryName(String countryName) { this.countryName = countryName; }

    public String getCountryFlag() { return countryFlag; }
    public void setCountryFlag(String countryFlag) { this.countryFlag = countryFlag; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public Long getGeonameId() { return geonameId; }
    public void setGeonameId(Long geonameId) { this.geonameId = geonameId; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

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
