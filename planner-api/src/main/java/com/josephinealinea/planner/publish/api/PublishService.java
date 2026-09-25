package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.notification.EmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.TripRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * A trip can be published at any point, however unfinished it is.
 *
 * By default only the owner can publish. Another member's Publish button
 * instead raises a request, and the owner's approval is what actually publishes.
 * With app.publish.require-owner-approval off, any member publishes directly.
 * Unpublishing is always the owner's.
 */

import java.util.ArrayList;


import java.util.List;
import java.util.Set;


@Service
public class PublishService {

    private final TripRepository trips;
    private final TripAccessService access;
    private final TripViewAssembler views;
    private final StaticSiteRenderer renderer;
    private final UserService users;
    private final EmailSender email;
    private final MailTemplates templates;
    private final PublishApprovalProperties approval;

    public PublishService(TripRepository trips,
                          TripAccessService access,
                          TripViewAssembler views,
                          StaticSiteRenderer renderer,
                          UserService users,
                          EmailSender email,
                          MailTemplates templates,
                          PublishApprovalProperties approval) {
        this.trips = trips;
        this.access = access;
        this.views = views;
        this.renderer = renderer;
        this.users = users;
        this.email = email;
        this.templates = templates;
        this.approval = approval;
    }

    /** Any member — the Publish tab reads the current state and request list. */
    public Trip publishView(String tripId, String userId) {
        return access.requireMember(tripId, userId);
    }

    /**
     * The owner, or any member when owner approval is switched off.
     * Re-publishing an already published trip just re-renders it.
     */
    public Trip publish(String tripId, String userId, String theme) {
        Trip trip = approval.requireOwnerApproval()
                ? access.requireOwner(tripId, userId)
                : access.requireMember(tripId, userId);
        return publishInternal(trip, userId, theme);
    }

    public Trip unpublish(String tripId, String userId) {
        Trip trip = access.requireOwner(tripId, userId);
        renderer.remove(trip.getSlug());
        trip.setStatus(TripStatus.DRAFT);
        trip.setPublishedAt(null);
        Audit.touched(trip, userId);
        return trips.save(trip);
    }

    /**
     * Any member who is not the owner. One pending request at a time.
     *
     * The page is built now, from the requesting member's own theme and
     * account settings, into the staging directory — so what eventually goes
     * public is what they asked to publish, not a rebuild using whatever the
     * owner happens to have configured. Nothing is reachable at the public URL
     * until the owner approves, and no URL is shown until then either
     * (TripViewAssembler.publicUrl answers null while the trip is a draft).
     */
    public Trip requestPublish(String tripId, String userId, String note, String theme) {
        Trip trip = access.requireMember(tripId, userId);
        if (!approval.requireOwnerApproval()) {
            throw ApiException.badRequest("error.publish.approvalNotNeeded");
        }
        if (trip.isOwner(userId)) {
            throw ApiException.badRequest("error.publish.ownerPublishesDirectly");
        }
        if (pendingRequest(trip).isPresent()) {
            throw ApiException.conflict("request_already_pending",
                    "error.publish.alreadyPending");
        }

        PublishRequest request = new PublishRequest();
        request.setId(Ids.newId());
        request.setRequestedByUserId(userId);
        request.setStatus(PublishRequest.Status.PENDING);
        request.setNote(note == null || note.isBlank() ? null : note.trim());
        request.setRequestedAt(Instant.now());
        request.setTheme(StaticSiteRenderer.safeTheme(theme));
        trip.getPublishRequests().add(request);
        Audit.touched(trip, userId);

        Trip saved = trips.save(trip);

        var requester = users.require(userId);
        renderer.renderPending(saved, optionsFor(requester), request.getTheme(),
                personalPagesFor(saved));

        var owner = users.require(trip.getOwnerUserId());
        email.send(templates.publishRequested(owner.getLanguageCode(),
                owner.getEmail(), trip.getTitle(), requester.displayName()));
        return saved;
    }

    /** The requester withdrawing their own pending request. */
    public Trip cancelRequest(String tripId, String userId, String requestId) {
        Trip trip = access.requireMember(tripId, userId);
        PublishRequest request = require(trip, requestId);

        if (!request.getRequestedByUserId().equals(userId)) {
            throw ApiException.forbidden("error.publish.onlyRequesterCancels");
        }
        if (!request.isPending()) {
            throw ApiException.conflict("already_decided", "error.publish.alreadyDecided");
        }
        request.setStatus(PublishRequest.Status.CANCELLED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);
        Audit.touched(trip, userId);
        // Withdrawn, so the page built for it is not going to be published.
        renderer.removePending(trip.getSlug());
        return trips.save(trip);
    }

    /** Owner only. Approving is what publishes. */
    public Trip approveRequest(String tripId, String userId, String requestId, String theme) {
        Trip trip = access.requireOwner(tripId, userId);
        PublishRequest request = requirePending(trip, requestId);

        request.setStatus(PublishRequest.Status.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);

        // The staged page goes live as it was built, so the theme recorded on
        // the trip is the requester's, not the approving owner's. The `theme`
        // argument is ignored for an approval for exactly that reason.
        boolean firstTime = !trip.isPublished();
        Trip published = goLive(trip, request, userId);

        var requester = users.require(request.getRequestedByUserId());
        email.send(templates.publishApproved(requester.getLanguageCode(),
                requester.getEmail(), trip.getTitle(), views.publicUrl(published),
                views.personalUrl(published, requester.getId())));
        if (firstTime) tellMembersItIsPublished(published, userId, Set.of(requester.getId()));
        return published;
    }

    public Trip rejectRequest(String tripId, String userId, String requestId) {
        Trip trip = access.requireOwner(tripId, userId);
        PublishRequest request = requirePending(trip, requestId);

        request.setStatus(PublishRequest.Status.REJECTED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);
        Audit.touched(trip, userId);
        renderer.removePending(trip.getSlug());
        Trip saved = trips.save(trip);

        var requester = users.require(request.getRequestedByUserId());
        email.send(templates.publishRejected(requester.getLanguageCode(), requester.getEmail(), trip.getTitle()));
        return saved;
    }

    /**
     * Takes an approved request's staged page live.
     *
     * A move, not a rebuild — see StaticSiteRenderer.promotePending. The
     * fallback matters: a request made before staging existed, or one whose
     * staged page was cleared, has nothing to move, and refusing to publish
     * would leave the owner with an approval that did nothing. It renders
     * instead, still using the *requester's* settings so the outcome does not
     * depend on which path ran.
     */
    private Trip goLive(Trip trip, PublishRequest request, String approverId) {
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setPublishedAt(Instant.now());
        Audit.touched(trip, approverId);
        Trip saved = trips.save(trip);

        if (!renderer.promotePending(saved.getSlug())) {
            var requester = users.require(request.getRequestedByUserId());
            renderer.render(saved, optionsFor(requester), request.getTheme(), personalPagesFor(saved));
        }
        return saved;
    }

    /**
     * A page for every member of the trip, in trip-member order.
     *
     * Each is rendered with <b>that member's</b> settings rather than the
     * publishing member's: it is their page, so what it reveals is their
     * choice — which is also why the same publish can put one member's costs
     * on their page and leave them off another's.
     *
     * The directory name comes from the display name, so it changes if they
     * change their screen name — a personal page is a file, and renaming
     * yourself republishes to a new one.
     */
    private List<StaticSiteRenderer.PersonalPage> personalPagesFor(Trip trip) {
        var accounts = users.byId(PersonalPages.memberIds(trip));
        List<StaticSiteRenderer.PersonalPage> pages = new ArrayList<>();
        PersonalPages.slugsFor(trip, accounts).forEach((memberId, memberSlug) ->
                pages.add(new StaticSiteRenderer.PersonalPage(
                        accounts.get(memberId), memberSlug, optionsFor(accounts.get(memberId)))));
        return pages;
    }

    /** A member's own published-page settings. */
    private PublishOptions optionsFor(com.josephinealinea.planner.identity.domain.User user) {
        var settings = user.getPublishedPage();
        return new PublishOptions(settings.isItineraryCost(),
                settings.isDestinationDays(),
                settings.isForecastExpenses(),
                settings.isDisplayBudget(),
                settings.isDisplayHomeCountry() && user.getHomeCountryCode() != null
                        ? java.util.List.of(user.getHomeCountryCode()) : java.util.List.of(),
                user.getLanguageCode());
    }

    /** The staged page's HTML for a members-only preview, or null if none. */
    public String previewPending(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return renderer.readPending(trip.getSlug());
    }

    private Trip publishInternal(Trip trip, String userId, String theme) {
        boolean firstTime = !trip.isPublished();
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setPublishedAt(Instant.now());
        Audit.touched(trip, userId);
        Trip saved = trips.save(trip);
        // The publishing member's own setting, not the owner's: they are the
        // one choosing to put this page up. Read at publish time, so changing
        // the checkbox takes effect on the next publish rather than
        // retroactively — a published page is a rendered file.
        renderer.render(saved, optionsFor(users.require(userId)), theme, personalPagesFor(saved));
        // Publishing directly supersedes anything staged for an undecided
        // request, so it must not be left behind to go live later.
        renderer.removePending(saved.getSlug());
        // Only when it goes live, not on every re-publish of a page that already is.
        if (firstTime) tellMembersItIsPublished(saved, userId, Set.of());
        return saved;
    }

    /**
     * Each member is told separately, with the link to their own page. Not the
     * person who did it, and not anybody in {@code alreadyTold}: on an approval
     * the requester has just been sent the approval, which says the same.
     */
    private void tellMembersItIsPublished(Trip trip, String actorId, Set<String> alreadyTold) {
        var accounts = users.byId(PersonalPages.memberIds(trip));
        String publicUrl = views.publicUrl(trip);
        accounts.forEach((memberId, member) -> {
            if (memberId.equals(actorId) || alreadyTold.contains(memberId)) return;
            email.send(templates.tripPublished(member.getLanguageCode(), member.getEmail(), trip.getTitle(),
                    publicUrl, views.personalUrl(trip, memberId)));
        });
    }

    private Optional<PublishRequest> pendingRequest(Trip trip) {
        return trip.getPublishRequests().stream().filter(PublishRequest::isPending).findFirst();
    }

    private PublishRequest require(Trip trip, String requestId) {
        return trip.getPublishRequests().stream()
                .filter(request -> request.getId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("error.publishRequest.notFound"));
    }

    private PublishRequest requirePending(Trip trip, String requestId) {
        PublishRequest request = require(trip, requestId);
        if (!request.isPending()) {
            throw ApiException.conflict("already_decided", "error.publish.alreadyDecided");
        }
        return request;
    }
}
