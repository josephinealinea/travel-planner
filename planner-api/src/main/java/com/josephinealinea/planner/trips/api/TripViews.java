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
                             String homeCountry,
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
                              String myPublicUrl,
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
                              List<PublishRequestView> requests,
                              /**
                               * The signed-in member's own published page, or
                               * null when they have not asked for one or the
                               * trip has not been published since they did.
                               *
                               * Checked against the file rather than against
                               * the setting: ticking the box takes effect on
                               * the next publish, like every other
                               * published-page setting, and a link offered
                               * before then would simply 404.
                               */
                              String myPublicUrl,
                              /**
                               * Whether a non-owner's Publish is a request the
                               * owner approves (true) or a publish (false).
                               * Server configuration, so the page follows it
                               * rather than assuming.
                               */
                              boolean requireOwnerApproval) {}

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
                             /**
                              * The signed-in member's part of each row they
                              * share, keyed by row id. Which rows the Budget
                              * tab lists is a lookup in here rather than a
                              * filter on `items`, which stays whole for the
                              * other tabs. See BudgetService.Summary.
                              */
                             Map<String, BigDecimal> shares,
                             BudgetService.Breakdown charged,
                             BudgetService.Breakdown forecast,
                             /**
                              * Who owes whom, from the charged rows alone, per
                              * other member and per currency. Member-only by
                              * construction: it is empty without a signed-in
                              * member, and a published page is built from
                              * PublishedTrip.Budget rather than this record, so
                              * no settlement has a route into a public file.
                              * See BudgetService.Settlement.
                              */
                             List<BudgetService.Settlement> settlements) {

        public static BudgetView from(BudgetService.Summary summary,
                                      com.josephinealinea.planner.rates.domain.RateTable table) {
            return new BudgetView(summary.items(), summary.displayCurrency(), summary.totalsCurrency(),
                    table.getBase(), table.getDate(), table.getRates(),
                    summary.shares(), summary.charged(), summary.forecast(),
                    summary.settlements());
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
                             PublishView publish,
                             /**
                              * Which destinations, checklist items and plans
                              * are the signed-in member's, for the Mine view.
                              * Decided here rather than in the page, the same
                              * way budget.shares is. See Travellers.
                              */
                             Travellers.Mine mine,
                             /** Who's going, by id, only where it is not the whole trip. */
                             Travellers.Named travellers) {}
}
