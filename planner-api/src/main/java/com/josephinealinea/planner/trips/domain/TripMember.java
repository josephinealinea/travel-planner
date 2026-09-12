package com.josephinealinea.planner.trips.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * The email is stored alongside the user id so the member list still reads
 * correctly without loading every user, and so the row survives if a user
 * record is ever missing.
 */
public class TripMember {

    private String userId;
    private String email;
    private TripRole role = TripRole.MEMBER;
    private String invitedByUserId;
    private Instant invitedAt;

    public TripMember() {}

    public TripMember(String userId, String email, TripRole role, String invitedByUserId) {
        this.userId = userId;
        this.email = email;
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

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public TripRole getRole() { return role; }
    public void setRole(TripRole role) { this.role = role; }

    public String getInvitedByUserId() { return invitedByUserId; }
    public void setInvitedByUserId(String invitedByUserId) { this.invitedByUserId = invitedByUserId; }

    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant invitedAt) { this.invitedAt = invitedAt; }
}
