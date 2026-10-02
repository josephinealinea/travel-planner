package com.josephinealinea.planner.identity.domain;

/**
 * How this account's own trip pages look to them in the planner.
 *
 * One document, like {@link PublishedPageSettings} — {@code trip_pages jsonb}
 * in Postgres, a nested mapping in {@code users.yml} — so the next setting is
 * a key rather than a column. Both flags are primitive booleans defaulting to
 * off, so a missing key reads as the planner behaving as it always has.
 *
 * These are views, not privacy: they change what the member is shown, never
 * what is stored or what a published page contains.
 */
public class TripPageSettings {

    /**
     * Whether the member may switch between their own activities and the whole
     * trip. Only a ROCKSTAR account can turn it on (enforced in UserService,
     * not just hidden in the page).
     */
    private boolean showWholeTrip;

    /**
     * Whether the trip pages offer the toggle that masks every amount. Off
     * means no toggle and amounts always shown.
     */
    private boolean maskAmounts;

    public TripPageSettings() {}

    public TripPageSettings(boolean showWholeTrip, boolean maskAmounts) {
        this.showWholeTrip = showWholeTrip;
        this.maskAmounts = maskAmounts;
    }

    public boolean isShowWholeTrip() { return showWholeTrip; }
    public void setShowWholeTrip(boolean showWholeTrip) { this.showWholeTrip = showWholeTrip; }

    public boolean isMaskAmounts() { return maskAmounts; }
    public void setMaskAmounts(boolean maskAmounts) { this.maskAmounts = maskAmounts; }
}
