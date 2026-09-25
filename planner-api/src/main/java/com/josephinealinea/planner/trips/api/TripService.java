package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.TierLevel;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.notification.EmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.i18n.Msg;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.publish.api.StaticSiteRenderer;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Service
public class TripService {

    private final TripRepository trips;
    private final TripAccessService access;
    private final UserService users;
    private final EmailSender email;
    private final MailTemplates templates;
    private final StaticSiteRenderer renderer;
    private final TripLimitProperties limits;
    private final MemberLinks links;

    public TripService(TripRepository trips,
                       TripAccessService access,
                       UserService users,
                       EmailSender email,
                       MailTemplates templates,
                       StaticSiteRenderer renderer,
                       TripLimitProperties limits,
                       MemberLinks links) {
        this.trips = trips;
        this.access = access;
        this.users = users;
        this.email = email;
        this.templates = templates;
        this.renderer = renderer;
        this.limits = limits;
        this.links = links;
    }

    /**
     * A BASIC account may be on a limited number of trips, the ones it created
     * and the ones it joined counted together. Other tiers are unlimited.
     */
    private boolean atTripLimit(User user) {
        return user.getTierLevel() == TierLevel.BASIC
                && trips.findAllForUser(user.getId()).size() >= limits.basicMaxTrips();
    }

    public List<Trip> listFor(String userId) {
        return trips.findAllForUser(userId);
    }

    public Trip create(String userId, String title, LocalDate startDate, LocalDate endDate) {
        requireDateOrder(startDate, endDate);
        User owner = users.require(userId);
        if (atTripLimit(owner)) {
            throw ApiException.conflict("trip_limit_reached",
                    "error.trip.limitReached", limits.basicMaxTrips());
        }

        Trip trip = new Trip();
        trip.setId(Ids.newId());
        trip.setSlug(Slugs.unique(title, trips::slugExists));
        trip.setTitle(title.trim());
        trip.setStartDate(startDate);
        trip.setEndDate(endDate);
        trip.setOwnerUserId(userId);
        trip.getMembers().add(new TripMember(userId, TripRole.OWNER, userId));
        Audit.created(trip, userId);
        return trips.save(trip);
    }

    public Trip update(String tripId,
                       String userId,
                       String title,
                       LocalDate startDate,
                       LocalDate endDate,
                       String displayCurrency) {
        Trip trip = access.requireMember(tripId, userId);

        if (title != null && !title.isBlank()) trip.setTitle(title.trim());
        if (startDate != null) trip.setStartDate(startDate);
        if (endDate != null) trip.setEndDate(endDate);
        requireDateOrder(trip.getStartDate(), trip.getEndDate());

        // Just a label now. Rates are fetched daily for the whole install and
        // quoted against their own base, so the display currency anchors
        // nothing and changing it cannot invalidate a stored number — which is
        // the entire reason rebase() used to exist.
        if (displayCurrency != null && !displayCurrency.isBlank()) {
            trip.setDisplayCurrency(displayCurrency.trim().toUpperCase());
        }

        Audit.touched(trip, userId);
        // The slug is deliberately not regenerated on a rename: it is the
        // storage filename and the published page's public URL.
        return trips.save(trip);
    }

    /**
     * Owner only — checked by the caller through TripAccessService.
     *
     * Takes the rendered public page with it. A published page is a plain
     * static directory with no trip behind it once the data is gone, so
     * nothing would ever remove it and it would keep serving the whole plan at
     * its public URL — the one place in this app where forgetting a cascade is
     * a privacy problem rather than a tidiness one. Deliberately first: if the
     * delete below fails the page is gone anyway, which is the safe direction
     * to fail in.
     */
    public void delete(String tripId, String userId) {
        Trip trip = access.requireOwner(tripId, userId);
        renderer.remove(trip.getSlug());
        // And anything staged for an undecided request, which is just as
        // readable a copy of the plan as the live one.
        renderer.removePending(trip.getSlug());
        trips.delete(trip);
    }

    /**
     * Adding a member always creates the account when there is not one already.
     * That is what lets the member list show an email for somebody who has never
     * signed in and a screen name for somebody who has, with no separate
     * "pending invite" concept to keep in step.
     */
    public Trip addMember(String tripId, String actingUserId, String memberEmail) {
        Trip trip = access.requireMember(tripId, actingUserId);
        UserService.Invited invited = users.findOrCreate(memberEmail);

        if (trip.isMember(invited.user().getId())) {
            throw ApiException.conflict("already_a_member",
                    "error.trip.alreadyMember", invited.user().displayName());
        }

        // A brand-new account is on no trips, so only an existing one can be full.
        if (!invited.created() && atTripLimit(invited.user())) {
            throw ApiException.conflict("member_trip_limit_reached",
                    "error.trip.memberLimitReached", invited.user().displayName(), limits.basicMaxTrips());
        }

        trip.getMembers().add(new TripMember(
                invited.user().getId(), TripRole.MEMBER, actingUserId));
        Audit.touched(trip, actingUserId);
        Trip saved = trips.save(trip);

        String invitedBy = users.require(actingUserId).displayName();
        email.send(invited.created()
                ? templates.invitedNewMember(invited.user().getLanguageCode(), invited.user().getEmail(), trip.getTitle(),
                                             invitedBy, invited.defaultPassword())
                : templates.addedExistingMember(invited.user().getLanguageCode(), invited.user().getEmail(), trip.getTitle(), invitedBy));

        return saved;
    }

    /**
     * The owner can remove any other member, and anyone can remove themselves,
     * which is how you leave a trip. The owner cannot be removed at all — the trip would be left
     * with nobody able to delete or publish it.
     */
    public Trip removeMember(String tripId, String actingUserId, String memberUserId) {
        Trip trip = access.requireMember(tripId, actingUserId);

        // Anyone may leave; only the owner may remove somebody else.
        if (!memberUserId.equals(actingUserId) && !trip.isOwner(actingUserId)) {
            throw ApiException.forbidden("error.trip.ownerRemovesBuddy");
        }

        TripMember member = trip.member(memberUserId)
                .orElseThrow(() -> ApiException.notFound("error.member.notFound"));

        if (member.isOwner()) {
            throw ApiException.conflict("owner_cannot_be_removed",
                    "error.trip.ownerCannotBeRemoved");
        }

        boolean leaving = memberUserId.equals(actingUserId);
        if (!leaving) {
            // The owner has to unlink somebody before removing them, so no
            // record is left naming a person who is no longer on the trip.
            List<String> areas = links.areasLinkedTo(trip.getSlug(), memberUserId);
            if (!areas.isEmpty()) {
                throw ApiException.conflict("member_still_linked",
                        "error.trip.memberStillLinked", users.require(memberUserId).displayName(), joined(areas));
            }
            trip.getMembers().removeIf(m -> m.getUserId().equals(memberUserId));
        } else if (links.inBudget(trip.getSlug(), memberUserId)) {
            // Their share of the budget stays: it moves to a stand-in that takes
            // their place on the trip, and they leave without it.
            User standIn = users.createLeftCopy(memberUserId);
            links.moveBudgetLinks(trip.getSlug(), memberUserId, standIn.getId());
            member.setUserId(standIn.getId());
        } else {
            trip.getMembers().removeIf(m -> m.getUserId().equals(memberUserId));
        }
        Audit.touched(trip, actingUserId);
        Trip saved = trips.save(trip);

        // Do not email somebody who just chose to leave.
        if (!memberUserId.equals(actingUserId)) {
            var removed = users.require(memberUserId);
            email.send(templates.removedFromTrip(removed.getLanguageCode(), removed.getEmail(), trip.getTitle()));
        }
        return saved;
    }

    /** "budget", "destinations" and "checklist" */
    private static Msg joined(List<String> areas) {
        Msg result = new Msg("area." + areas.get(0));
        for (int i = 1; i < areas.size(); i++) {
            String join = i == areas.size() - 1 ? "list.and" : "list.comma";
            result = new Msg(join, result, new Msg("area." + areas.get(i)));
        }
        return result;
    }

    private static void requireDateOrder(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw ApiException.badRequest("error.trip.datesRequired");
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("error.dates.endBeforeStart");
        }
    }
}
