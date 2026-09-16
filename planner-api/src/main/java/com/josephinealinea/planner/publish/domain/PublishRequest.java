package com.josephinealinea.planner.publish.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * A non-owner member asking the owner to publish. Lives inside the trip file
 * because it is only ever read with the trip.
 */
public class PublishRequest {

    public enum Status { PENDING, APPROVED, REJECTED, CANCELLED }

    private String id;
    private String requestedByUserId;
    private Status status = Status.PENDING;
    private String note;
    private Instant requestedAt;
    private Instant decidedAt;
    private String decidedByUserId;

    /**
     * The theme the staged page was rendered in — the requester's own, read
     * from their browser when they asked.
     *
     * Kept because approving moves that page into place rather than rebuilding
     * it, so this is the only record of what the live page actually looks like.
     */
    private String theme;

    public PublishRequest() {}

    @JsonIgnore
    public boolean isPending() {
        return status == Status.PENDING;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRequestedByUserId() { return requestedByUserId; }
    public void setRequestedByUserId(String requestedByUserId) { this.requestedByUserId = requestedByUserId; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }

    public String getDecidedByUserId() { return decidedByUserId; }
    public void setDecidedByUserId(String decidedByUserId) { this.decidedByUserId = decidedByUserId; }
}
