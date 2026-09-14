package com.josephinealinea.planner.trips.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.shared.Audited;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The trip itself, plus its members and publish requests — they are only ever
 * read and written together, so they share one file.
 */
public class Trip implements Audited {

    private String id;
    private String slug;
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private String ownerUserId;
    private TripStatus status = TripStatus.DRAFT;

    /** Display currency for budget rollups, plus hand-maintained rates into it. */
    private String displayCurrency = "EUR";
    private Map<String, BigDecimal> exchangeRates = new LinkedHashMap<>();

    /** Which frontend theme the published page is rendered in. */
    private String publishedTheme = "minima";
    private Instant publishedAt;

    private List<TripMember> members = new ArrayList<>();
    private List<PublishRequest> publishRequests = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
    // The timestamps above are stamped by YamlTripRepository on every
    // save; these two come from the service, which is the layer that
    // knows who is asking. See Audit.
    private String createdByUserId;
    private String updatedByUserId;

    public Trip() {}

    public Optional<TripMember> member(String userId) {
        return members.stream().filter(m -> m.getUserId().equals(userId)).findFirst();
    }

    public boolean isMember(String userId) {
        return member(userId).isPresent();
    }

    public boolean isOwner(String userId) {
        return ownerUserId != null && ownerUserId.equals(userId);
    }

    @JsonIgnore
    public boolean isPublished() {
        return status == TripStatus.PUBLISHED;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }

    public TripStatus getStatus() { return status; }
    public void setStatus(TripStatus status) { this.status = status; }

    public String getDisplayCurrency() { return displayCurrency; }
    public void setDisplayCurrency(String displayCurrency) { this.displayCurrency = displayCurrency; }

    public Map<String, BigDecimal> getExchangeRates() { return exchangeRates; }
    public void setExchangeRates(Map<String, BigDecimal> exchangeRates) {
        this.exchangeRates = exchangeRates == null ? new LinkedHashMap<>() : exchangeRates;
    }

    public String getPublishedTheme() { return publishedTheme; }
    public void setPublishedTheme(String publishedTheme) { this.publishedTheme = publishedTheme; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public List<TripMember> getMembers() { return members; }
    public void setMembers(List<TripMember> members) {
        this.members = members == null ? new ArrayList<>() : members;
    }

    public List<PublishRequest> getPublishRequests() { return publishRequests; }
    public void setPublishRequests(List<PublishRequest> publishRequests) {
        this.publishRequests = publishRequests == null ? new ArrayList<>() : publishRequests;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(String updatedByUserId) { this.updatedByUserId = updatedByUserId; }
}
