package com.josephinealinea.planner.publish.api;

/**
 * What a published page is allowed to show, from the publishing member's
 * account settings.
 *
 * A record rather than a growing list of boolean parameters: these are all
 * flags, more are expected, and `render(trip, false, true)` at the call site
 * says nothing about which is which.
 *
 * Every flag is off by default — see {@link #hidden()}. A published page is
 * public, so anything extra it reveals is asked for rather than assumed, and a
 * value that is switched off is left out of the payload entirely rather than
 * hidden in the page.
 */
public record PublishOptions(boolean itineraryCost,
                             boolean destinationDays,
                             boolean forecastExpenses) {

    /** Everything off, which is also what an unconfigured account gets. */
    public static PublishOptions hidden() {
        return new PublishOptions(false, false, false);
    }
}
