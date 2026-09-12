package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.notification.EmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.shared.Slugs;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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

    public TripService(TripRepository trips,
                       TripAccessService access,
                       UserService users,
                       EmailSender email,
                       MailTemplates templates) {
        this.trips = trips;
        this.access = access;
        this.users = users;
        this.email = email;
        this.templates = templates;
    }

    public List<Trip> listFor(String userId) {
        return trips.findAllForUser(userId);
    }

    public Trip create(String userId, String title, LocalDate startDate, LocalDate endDate) {
        requireDateOrder(startDate, endDate);
        User owner = users.require(userId);

        Trip trip = new Trip();
        trip.setId(Ids.newId());
        trip.setSlug(Slugs.unique(title, trips::slugExists));
        trip.setTitle(title.trim());
        trip.setStartDate(startDate);
        trip.setEndDate(endDate);
        trip.setOwnerUserId(userId);
        trip.getMembers().add(new TripMember(userId, owner.getEmail(), TripRole.OWNER, userId));
        return trips.save(trip);
    }

    public Trip update(String tripId,
                       String userId,
                       String title,
                       LocalDate startDate,
                       LocalDate endDate,
                       String displayCurrency,
                       Map<String, BigDecimal> exchangeRates) {
        Trip trip = access.requireMember(tripId, userId);

        if (title != null && !title.isBlank()) trip.setTitle(title.trim());
        if (startDate != null) trip.setStartDate(startDate);
        if (endDate != null) trip.setEndDate(endDate);
        requireDateOrder(trip.getStartDate(), trip.getEndDate());

        if (displayCurrency != null && !displayCurrency.isBlank()) {
            trip.setDisplayCurrency(displayCurrency.trim().toUpperCase());
        }
        if (exchangeRates != null) trip.setExchangeRates(exchangeRates);

        // The slug is deliberately not regenerated on a rename: it is the
        // storage filename and the published page's public URL.
        return trips.save(trip);
    }

    /** Owner only — checked by the caller through TripAccessService. */
    public void delete(String tripId, String userId) {
        trips.delete(access.requireOwner(tripId, userId));
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
                    "%s is already on this trip.".formatted(invited.user().displayName()));
        }

        trip.getMembers().add(new TripMember(
                invited.user().getId(), invited.user().getEmail(), TripRole.MEMBER, actingUserId));
        Trip saved = trips.save(trip);

        String invitedBy = users.require(actingUserId).displayName();
        email.send(invited.created()
                ? templates.invitedNewMember(invited.user().getEmail(), trip.getTitle(),
                                             invitedBy, invited.defaultPassword())
                : templates.addedExistingMember(invited.user().getEmail(), trip.getTitle(), invitedBy));

        return saved;
    }

    /**
     * Any member can remove any other member, and removing yourself is how you
     * leave a trip. The owner cannot be removed at all — the trip would be left
     * with nobody able to delete or publish it.
     */
    public Trip removeMember(String tripId, String actingUserId, String memberUserId) {
        Trip trip = access.requireMember(tripId, actingUserId);

        TripMember member = trip.member(memberUserId)
                .orElseThrow(() -> ApiException.notFound("Member"));

        if (member.isOwner()) {
            throw ApiException.conflict("owner_cannot_be_removed",
                    "The trip owner cannot be removed. Delete the trip instead.");
        }

        trip.getMembers().removeIf(m -> m.getUserId().equals(memberUserId));
        Trip saved = trips.save(trip);

        // Do not email somebody who just chose to leave.
        if (!memberUserId.equals(actingUserId)) {
            email.send(templates.removedFromTrip(member.getEmail(), trip.getTitle()));
        }
        return saved;
    }

    private static void requireDateOrder(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw ApiException.badRequest("A trip needs both a start and an end date.");
        }
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date cannot be before the start date.");
        }
    }
}
