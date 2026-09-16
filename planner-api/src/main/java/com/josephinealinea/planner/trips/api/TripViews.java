package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.domain.TripStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The response shapes. Kept apart from the domain so storage can change freely. */
public final class TripViews {

    private TripViews() {}

    /**
     * A member row. displayName is the screen name once they have set one and
     * their email until then; hasSignedIn drives the "not signed in yet" chip.
     */
    public record MemberView(String userId,
                             String email,
                             String displayName,
                             String screenName,
                             TripRole role,
                             boolean hasSignedIn,
                             Instant invitedAt) {}

    /** One row in the trip list. */
    public record TripSummary(String id,
                              String slug,
                              String title,
                              LocalDate startDate,
                              LocalDate endDate,
                              TripStatus status,
                              boolean isOwner,
                              String ownerDisplayName,
                              int memberCount,
                              int checklistTotal,
                              int checklistCompleted,
                              String publicUrl,
                              Instant publishedAt) {}

    public record PublishRequestView(String id,
                                     String requestedByUserId,
                                     String requestedByDisplayName,
                                     PublishRequest.Status status,
                                     Instant requestedAt,
                                     Instant decidedAt) {}

    public record PublishView(TripStatus status,
                              String publicUrl,
                              Instant publishedAt,
                              String publishedTheme,
                              List<PublishRequestView> requests) {}

    /**
     * displayCurrency is the trip's own anchor (what its exchange-rate table
     * is quoted against, and what "record a cost" forms default to);
     * totalsCurrency is what both breakdowns are actually expressed in — the
     * signed-in member's own display-currency preference, falling back to the
     * anchor. See BudgetService.Summary.
     *
     * `charged` and `forecast` are the same rollup over different rows: the
     * charges alone, and the charges plus everything still to be paid. They are
     * passed through whole rather than flattened, so a total can never be shown
     * against a breakdown of some other set of rows — see BudgetService.Breakdown.
     */
    public record BudgetView(List<BudgetItem> items,
                             String displayCurrency,
                             String totalsCurrency,
                             /**
                              * The currency every rate below is quoted against.
                              * Not the trip's display currency any more — rates
                              * are fetched for the whole install against their
                              * own base, so the page has to be told what to
                              * pivot through. See RateTable.
                              */
                             String ratesBase,
                             String ratesDate,
                             Map<String, BigDecimal> exchangeRates,
                             BudgetService.Breakdown charged,
                             BudgetService.Breakdown forecast) {

        public static BudgetView from(BudgetService.Summary summary,
                                      com.josephinealinea.planner.rates.domain.RateTable table) {
            return new BudgetView(summary.items(), summary.displayCurrency(), summary.totalsCurrency(),
                    table.getBase(), table.getDate(), table.getRates(),
                    summary.charged(), summary.forecast());
        }
    }

    /**
     * Everything the trip workspace needs, in one request — the tabs all read
     * from this rather than each fetching separately.
     */
    public record TripDetail(String id,
                             String slug,
                             String title,
                             LocalDate startDate,
                             LocalDate endDate,
                             TripStatus status,
                             String ownerUserId,
                             boolean isOwner,
                             String currentUserId,
                             List<MemberView> members,
                             List<Destination> destinations,
                             List<ChecklistItem> checklist,
                             List<ItineraryItem> itinerary,
                             BudgetView budget,
                             PublishView publish) {}
}
