package com.josephinealinea.planner.identity.domain;

/**
 * What this account's published pages show.
 *
 * One object rather than a field per checkbox, stored as a single document —
 * {@code published_page jsonb} in Postgres, a nested mapping in
 * {@code users.yml} — so the next setting is a key inside it rather than a
 * column, a migration and four more places to thread it through.
 *
 * Every flag is a primitive {@code boolean} defaulting to off, for two
 * reasons that both matter: a published page is public, so anything extra it
 * reveals should be asked for rather than assumed; and {@code YamlStore}
 * serialises NON_NULL, so a {@code Boolean} would be absent from the file
 * until first set, and a setting you cannot see in the file is one nobody
 * knows is there.
 */
public class PublishedPageSettings {

    /** Whether a published itinerary entry shows what it cost. */
    private boolean itineraryCost;

    /**
     * Whether a published destination card counts days rather than nights.
     *
     * Both are derived from the same two dates and neither is stored — see the
     * "Nights versus days" note in CLAUDE.md. This only chooses which one a
     * reader is shown.
     */
    private boolean destinationDays;

    /**
     * Whether a published page offers its budget's forecast breakdowns — the
     * ones that count expenses still to be paid alongside the charges.
     *
     * Off by default, and when it is off the page carries no forecast figure at
     * all rather than one it renders and hides: what a trip is still going to
     * cost is a more private number than what it has cost so far.
     */
    private boolean forecastExpenses;

    /**
     * Whether a published page opens its Destinations section with the
     * member's home country. The section is then labelled "Home &amp;
     * Destinations". Off by default, and a page with it off carries no home
     * country at all.
     */
    private boolean displayHomeCountry;

    /**
     * Whether a published page has a Budget section — and a Budget filter — at
     * all. Off by default: when it is off the page carries no budget figure,
     * not one it renders and hides.
     */
    private boolean displayBudget;

    public PublishedPageSettings() {}

    public PublishedPageSettings(boolean itineraryCost, boolean destinationDays, boolean forecastExpenses,
                                 boolean displayHomeCountry, boolean displayBudget) {
        this.itineraryCost = itineraryCost;
        this.destinationDays = destinationDays;
        this.forecastExpenses = forecastExpenses;
        this.displayHomeCountry = displayHomeCountry;
        this.displayBudget = displayBudget;
    }

    public boolean isItineraryCost() { return itineraryCost; }
    public void setItineraryCost(boolean itineraryCost) { this.itineraryCost = itineraryCost; }

    public boolean isDestinationDays() { return destinationDays; }
    public void setDestinationDays(boolean destinationDays) { this.destinationDays = destinationDays; }

    public boolean isForecastExpenses() { return forecastExpenses; }
    public void setForecastExpenses(boolean forecastExpenses) { this.forecastExpenses = forecastExpenses; }

    public boolean isDisplayHomeCountry() { return displayHomeCountry; }
    public void setDisplayHomeCountry(boolean displayHomeCountry) { this.displayHomeCountry = displayHomeCountry; }

    public boolean isDisplayBudget() { return displayBudget; }
    public void setDisplayBudget(boolean displayBudget) { this.displayBudget = displayBudget; }
}
