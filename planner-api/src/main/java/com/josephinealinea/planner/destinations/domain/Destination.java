package com.josephinealinea.planner.destinations.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.shared.Nights;

import java.time.Instant;
import java.time.LocalDate;

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
public class Destination {

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
    private String notes;
    private int sortOrder;
    private Instant createdAt;

    public Destination() {}

    public Long nights() {
        return Nights.between(startDate, endDate);
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

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
