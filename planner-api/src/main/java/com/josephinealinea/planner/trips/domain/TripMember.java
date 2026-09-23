package com.josephinealinea.planner.trips.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * A membership: which user, in what role, invited by whom. The email and screen
 * name belong to the user record and are read from there, so changing either
 * takes effect on every trip at once.
 */
public class TripMember {

    private String userId;
    private TripRole role = TripRole.MEMBER;
    private String invitedByUserId;
    private Instant invitedAt;

    public TripMember() {}

    public TripMember(String userId, TripRole role, String invitedByUserId) {
        this.userId = userId;
        this.role = role;
        this.invitedByUserId = invitedByUserId;
        this.invitedAt = Instant.now();
    }

    @JsonIgnore
    public boolean isOwner() {
        return role == TripRole.OWNER;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public TripRole getRole() { return role; }
    public void setRole(TripRole role) { this.role = role; }

    public String getInvitedByUserId() { return invitedByUserId; }
    public void setInvitedByUserId(String invitedByUserId) { this.invitedByUserId = invitedByUserId; }

    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant invitedAt) { this.invitedAt = invitedAt; }
}
