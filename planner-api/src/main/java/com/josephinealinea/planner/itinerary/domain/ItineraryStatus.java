package com.josephinealinea.planner.itinerary.domain;

/**
 * Whether an itinerary entry (or the plan it belongs to) is a settled plan or
 * still a proposal — "suggested to the herd" rather than decided.
 *
 * Mirrors budget.domain.BudgetStatus exactly, for the same reason: every row
 * written before this field existed was somebody's actual plan, never a
 * proposal, so a record with no status in its YAML must read as FINAL.
 */
public enum ItineraryStatus {
    FINAL,
    PENDING
}
