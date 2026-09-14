package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turns stored records into the response views, resolving display names once. */
@Service
public class TripViewAssembler {

    private final UserRepository users;
    private final DestinationRepository destinations;
    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final BudgetService budgets;
    private final RatesService rates;
    private final String publicBaseUrl;

    public TripViewAssembler(UserRepository users,
                             DestinationRepository destinations,
                             ChecklistRepository checklist,
                             ItineraryRepository itinerary,
                             BudgetService budgets,
                             RatesService rates,
                             AppProperties props) {
        this.users = users;
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budgets = budgets;
        this.rates = rates;
        this.publicBaseUrl = props.publish().publicBaseUrl();
    }

    public String publicUrl(Trip trip) {
        if (!trip.isPublished()) return null;
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        return base + trip.getSlug();
    }

    public TripViews.TripSummary summary(Trip trip, String currentUserId) {
        List<ChecklistItem> items = checklist.findAll(trip.getSlug());
        long completed = items.stream().filter(ChecklistItem::isCompleted).count();

        return new TripViews.TripSummary(
                trip.getId(),
                trip.getSlug(),
                trip.getTitle(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getStatus(),
                trip.isOwner(currentUserId),
                displayName(trip.getOwnerUserId()).orElse("the owner"),
                trip.getMembers().size(),
                items.size(),
                (int) completed,
                publicUrl(trip),
                trip.getPublishedAt());
    }

    public TripViews.TripDetail detail(Trip trip, String currentUserId) {
        // Totals show in the viewer's own display-currency preference; the
        // trip's own displayCurrency (its rate anchor) is unaffected by who
        // is looking.
        User currentUser = currentUserId == null ? null : users.findById(currentUserId).orElse(null);
        BudgetService.Summary budget = budgets.summarise(trip, currentUser);

        return new TripViews.TripDetail(
                trip.getId(),
                trip.getSlug(),
                trip.getTitle(),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.getStatus(),
                trip.getOwnerUserId(),
                trip.isOwner(currentUserId),
                currentUserId,
                members(trip),
                destinations.findAllOrdered(trip.getSlug()),
                checklist.findAllOrdered(trip.getSlug()),
                itinerary.findAllOrdered(trip.getSlug()),
                TripViews.BudgetView.from(budget, rates.current()),
                publish(trip));
    }

    public List<TripViews.MemberView> members(Trip trip) {
        Map<String, User> byId = usersById(trip);

        return trip.getMembers().stream().map(member -> {
            User user = byId.get(member.getUserId());
            // Clearing the forced password change is the moment somebody has
            // actually been through first sign-in. Whether they went on to set
            // a screen name is a separate thing, carried by screenName — the
            // display name already falls back to the email without one.
            boolean hasSignedIn = user != null && !user.isMustChangePassword();
            return new TripViews.MemberView(
                    member.getUserId(),
                    member.getEmail(),
                    user == null ? member.getEmail() : user.displayName(),
                    user == null ? null : user.getScreenName(),
                    member.getRole(),
                    hasSignedIn,
                    member.getInvitedAt());
        }).toList();
    }

    public TripViews.PublishView publish(Trip trip) {
        List<TripViews.PublishRequestView> requests = trip.getPublishRequests().stream()
                .map(request -> new TripViews.PublishRequestView(
                        request.getId(),
                        request.getRequestedByUserId(),
                        displayName(request.getRequestedByUserId()).orElse("a member"),
                        request.getStatus(),
                        request.getRequestedAt(),
                        request.getDecidedAt()))
                .toList();

        return new TripViews.PublishView(
                trip.getStatus(),
                publicUrl(trip),
                trip.getPublishedAt(),
                trip.getPublishedTheme(),
                requests);
    }

    private Map<String, User> usersById(Trip trip) {
        List<String> ids = trip.getMembers().stream().map(TripMember::getUserId).toList();
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private Optional<String> displayName(String userId) {
        return userId == null ? Optional.empty() : users.findById(userId).map(User::displayName);
    }
}
