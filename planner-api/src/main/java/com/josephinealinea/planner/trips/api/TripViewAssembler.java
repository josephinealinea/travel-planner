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
import com.josephinealinea.planner.publish.api.PersonalPages;
import com.josephinealinea.planner.publish.api.PublishApprovalProperties;
import com.josephinealinea.planner.publish.infra.PageStore;
import com.josephinealinea.planner.shared.Emails;
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
    private final PageStore pages;
    private final String publicBaseUrl;
    private final PublishApprovalProperties approval;

    public TripViewAssembler(UserRepository users,
                             DestinationRepository destinations,
                             ChecklistRepository checklist,
                             ItineraryRepository itinerary,
                             BudgetService budgets,
                             RatesService rates,
                             PageStore pages,
                             AppProperties props,
                             PublishApprovalProperties approval) {
        this.approval = approval;
        this.users = users;
        this.destinations = destinations;
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.budgets = budgets;
        this.rates = rates;
        this.pages = pages;
        this.publicBaseUrl = props.publish().publicBaseUrl();
    }

    public String publicUrl(Trip trip) {
        if (!trip.isPublished()) return null;
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        return base + trip.getSlug();
    }

    public TripViews.TripSummary summary(Trip trip, String currentUserId) {
        List<ChecklistItem> all = checklist.findAll(trip.getSlug());
        // The card agrees with the trip's default Mine view: this member's items.
        Travellers travellers = Travellers.of(trip, destinations.findAll(trip.getSlug()), all, List.of());
        List<ChecklistItem> items = currentUserId == null ? all : all.stream()
                .filter(item -> Travellers.includes(travellers.ofChecklistItem(item), currentUserId))
                .toList();
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
                personalUrl(trip, currentUserId),
                trip.getPublishedAt());
    }

    public TripViews.TripDetail detail(Trip trip, String currentUserId) {
        // Totals show in the viewer's own display-currency preference; the
        // trip's own displayCurrency (its rate anchor) is unaffected by who
        // is looking.
        User currentUser = currentUserId == null ? null : users.findById(currentUserId).orElse(null);
        BudgetService.Summary budget = budgets.summarise(trip, currentUser);
        var tripDestinations = destinations.findAllOrdered(trip.getSlug());
        var tripChecklist = checklist.findAllOrdered(trip.getSlug());
        var tripItinerary = itinerary.findAllOrdered(trip.getSlug());
        Travellers travellers = Travellers.of(trip, tripDestinations, tripChecklist, tripItinerary);

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
                tripDestinations,
                tripChecklist,
                tripItinerary,
                TripViews.BudgetView.from(budget, rates.current()),
                publish(trip, currentUserId),
                travellers.mineFor(currentUserId),
                travellers.named());
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
                    user == null ? null : Emails.shorten(user.getEmail()),
                    user == null ? "Former member" : user.displayName(),
                    user == null ? null : user.getScreenName(),
                    user == null ? null : user.getHomeCountryCode(),
                    member.getRole(),
                    hasSignedIn,
                    member.getInvitedAt());
        }).toList();
    }

    public TripViews.PublishView publish(Trip trip, String currentUserId) {
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
                requests,
                personalUrl(trip, currentUserId),
                approval.requireOwnerApproval(),
                allPersonalUrls(trip, currentUserId));
    }

    /**
     * Every personal page actually written, for the owner. Built from the same
     * slugs the publisher names the folders with and checked against one
     * listing of the store, so a member added since the last publish is left
     * out rather than offered as a 404.
     */
    private List<String> allPersonalUrls(Trip trip, String currentUserId) {
        if (currentUserId == null || !trip.isPublished() || !trip.isOwner(currentUserId)) {
            return List.of();
        }
        var written = pages.publishedMemberPages(trip.getSlug());
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        return PersonalPages.slugsFor(trip, usersById(trip)).values().stream()
                .filter(written::contains)
                .sorted()
                .map(slug -> base + trip.getSlug() + "/m/" + slug)
                .toList();
    }

    /**
     * The signed-in member's own page, if one has actually been written.
     *
     * The file check is the point: the box takes effect on the next publish, so
     * between ticking it and republishing there is a setting that is on and no
     * page behind it. Offering the link anyway would hand somebody a 404 to
     * share.
     *
     * One listing of the trip's personal pages rather than an existence check
     * for this member: against R2 every check is a network round trip, and
     * this runs on every trip load.
     */
    private String personalUrl(Trip trip, String currentUserId) {
        if (currentUserId == null || !trip.isPublished()) return null;
        String memberSlug = PersonalPages
                .slugsFor(trip, usersById(trip))
                .get(currentUserId);
        if (memberSlug == null) return null;
        if (!pages.publishedMemberPages(trip.getSlug()).contains(memberSlug)) return null;
        String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
        return base + trip.getSlug() + "/m/" + memberSlug;
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
