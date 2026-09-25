package com.josephinealinea.planner.identity.domain;

import com.josephinealinea.planner.shared.Emails;

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
    /** ISO 3166-1 alpha-2 code, named by the shared country table; blank until they set one. */
    private String homeCountryCode;
    /**
     * The language this member reads the app in, a code with a messages file
     * (see {@code i18n.Messages}). Null until they choose one, which means
     * "whatever the browser asks for, else English" rather than a stored "en".
     */
    private String languageCode;

    /**
     * Never null: a users.yml record with no tier, or a row written before the
     * column existed, is BASIC, which is what every account started as.
     */
    private TierLevel tierLevel = TierLevel.BASIC;

    private String passwordHash;
    private boolean mustChangePassword;
    private List<String> currencies = new ArrayList<>();
    private String displayCurrency;

    /**
     * Published-page settings, one document rather than a field per checkbox.
     * Account-level rather than per trip: they are a preference about how this
     * member shares their plans, not a property of any one trip.
     *
     * Never null — a missing key in users.yml, or a row written before this
     * existed, reads as every flag off, which is what every one of them
     * defaults to anyway.
     */
    private PublishedPageSettings publishedPage = new PublishedPageSettings();

    private Instant createdAt;
    private Instant updatedAt;

    public User() {}

    public String displayName() {
        return (screenName == null || screenName.isBlank()) ? Emails.shorten(email) : screenName;
    }

    public String getLanguageCode() { return languageCode; }
    public void setLanguageCode(String languageCode) { this.languageCode = languageCode; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getHomeCountryCode() { return homeCountryCode; }
    public void setHomeCountryCode(String homeCountryCode) { this.homeCountryCode = homeCountryCode; }

    public TierLevel getTierLevel() { return tierLevel; }
    public void setTierLevel(TierLevel tierLevel) { this.tierLevel = tierLevel == null ? TierLevel.BASIC : tierLevel; }

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

    public PublishedPageSettings getPublishedPage() { return publishedPage; }
    public void setPublishedPage(PublishedPageSettings publishedPage) {
        this.publishedPage = publishedPage == null ? new PublishedPageSettings() : publishedPage;
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
