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

    public record BudgetView(List<BudgetItem> items,
                             String displayCurrency,
                             Map<String, BigDecimal> exchangeRates,
                             Map<String, BigDecimal> byCategory,
                             BigDecimal total,
                             List<String> currenciesMissingRates) {

        public static BudgetView from(BudgetService.Summary summary, Map<String, BigDecimal> rates) {
            return new BudgetView(summary.items(), summary.displayCurrency(), rates,
                    summary.byCategory(), summary.total(), summary.currenciesMissingRates());
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
