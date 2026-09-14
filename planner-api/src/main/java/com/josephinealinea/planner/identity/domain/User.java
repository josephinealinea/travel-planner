package com.josephinealinea.planner.identity.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable on purpose: the YAML store reads a list of these, mutates one, and
 * writes the list back. Jackson needs the no-arg constructor plus setters.
 *
 * displayName() is the single rule behind "show the email until they have a
 * screen name" — because adding a trip member always creates the account, a
 * member with no screen name is exactly a member who has never signed in.
 */
public class User {

    private String id;
    private String email;
    private String screenName;
    private String passwordHash;
    private boolean mustChangePassword;
    private List<String> currencies = new ArrayList<>();
    private String displayCurrency;
    private Instant createdAt;
    private Instant updatedAt;

    public User() {}

    public String displayName() {
        return (screenName == null || screenName.isBlank()) ? email : screenName;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getScreenName() { return screenName; }
    public void setScreenName(String screenName) { this.screenName = screenName; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }

    public List<String> getCurrencies() { return currencies; }
    public void setCurrencies(List<String> currencies) {
        this.currencies = currencies == null ? new ArrayList<>() : currencies;
    }

    /**
     * The currency this user's budget totals are shown in — independent of
     * currencies above (what "record a cost" forms offer) and of any trip's
     * own displayCurrency (the anchor its exchange-rate table is quoted
     * against). Seeded from app.currencies.defaultDisplay; null only for an
     * account created before this field existed, which callers treat as
     * "fall back to the trip's own currency".
     */
    public String getDisplayCurrency() { return displayCurrency; }
    public void setDisplayCurrency(String displayCurrency) { this.displayCurrency = displayCurrency; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
