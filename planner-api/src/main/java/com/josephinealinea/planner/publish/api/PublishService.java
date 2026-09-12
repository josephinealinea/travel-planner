package com.josephinealinea.planner.publish.api;

import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.notification.EmailSender;
import com.josephinealinea.planner.notification.MailTemplates;
import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.shared.ApiException;
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
 * Only the owner can publish. Another member's Publish button instead raises a
 * request, and the owner's approval is what actually publishes.
 */
@Service
public class PublishService {

    private final TripRepository trips;
    private final TripAccessService access;
    private final TripViewAssembler views;
    private final StaticSiteRenderer renderer;
    private final UserService users;
    private final EmailSender email;
    private final MailTemplates templates;

    public PublishService(TripRepository trips,
                          TripAccessService access,
                          TripViewAssembler views,
                          StaticSiteRenderer renderer,
                          UserService users,
                          EmailSender email,
                          MailTemplates templates) {
        this.trips = trips;
        this.access = access;
        this.views = views;
        this.renderer = renderer;
        this.users = users;
        this.email = email;
        this.templates = templates;
    }

    /** Any member — the Publish tab reads the current state and request list. */
    public Trip publishView(String tripId, String userId) {
        return access.requireMember(tripId, userId);
    }

    /** Owner only. Re-publishing an already published trip just re-renders it. */
    public Trip publish(String tripId, String userId, String theme) {
        Trip trip = access.requireOwner(tripId, userId);
        return publishInternal(trip, theme);
    }

    public Trip unpublish(String tripId, String userId) {
        Trip trip = access.requireOwner(tripId, userId);
        renderer.remove(trip.getSlug());
        trip.setStatus(TripStatus.DRAFT);
        trip.setPublishedAt(null);
        return trips.save(trip);
    }

    /** Any member who is not the owner. One pending request at a time. */
    public Trip requestPublish(String tripId, String userId, String note) {
        Trip trip = access.requireMember(tripId, userId);
        if (trip.isOwner(userId)) {
            throw ApiException.badRequest("You own this trip — publish it directly.");
        }
        if (pendingRequest(trip).isPresent()) {
            throw ApiException.conflict("request_already_pending",
                    "There is already a publish request waiting for the owner.");
        }

        PublishRequest request = new PublishRequest();
        request.setId(Ids.newId());
        request.setRequestedByUserId(userId);
        request.setStatus(PublishRequest.Status.PENDING);
        request.setNote(note == null || note.isBlank() ? null : note.trim());
        request.setRequestedAt(Instant.now());
        trip.getPublishRequests().add(request);

        Trip saved = trips.save(trip);

        var owner = users.require(trip.getOwnerUserId());
        email.send(templates.publishRequested(
                owner.getEmail(), trip.getTitle(), users.require(userId).displayName()));
        return saved;
    }

    /** The requester withdrawing their own pending request. */
    public Trip cancelRequest(String tripId, String userId, String requestId) {
        Trip trip = access.requireMember(tripId, userId);
        PublishRequest request = require(trip, requestId);

        if (!request.getRequestedByUserId().equals(userId)) {
            throw ApiException.forbidden("Only the member who asked can cancel that request.");
        }
        if (!request.isPending()) {
            throw ApiException.conflict("already_decided", "That request has already been decided.");
        }
        request.setStatus(PublishRequest.Status.CANCELLED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);
        return trips.save(trip);
    }

    /** Owner only. Approving is what publishes. */
    public Trip approveRequest(String tripId, String userId, String requestId, String theme) {
        Trip trip = access.requireOwner(tripId, userId);
        PublishRequest request = requirePending(trip, requestId);

        request.setStatus(PublishRequest.Status.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);

        Trip published = publishInternal(trip, theme);

        var requester = users.require(request.getRequestedByUserId());
        email.send(templates.publishApproved(
                requester.getEmail(), trip.getTitle(), views.publicUrl(published)));
        return published;
    }

    public Trip rejectRequest(String tripId, String userId, String requestId) {
        Trip trip = access.requireOwner(tripId, userId);
        PublishRequest request = requirePending(trip, requestId);

        request.setStatus(PublishRequest.Status.REJECTED);
        request.setDecidedAt(Instant.now());
        request.setDecidedByUserId(userId);
        Trip saved = trips.save(trip);

        var requester = users.require(request.getRequestedByUserId());
        email.send(templates.publishRejected(requester.getEmail(), trip.getTitle()));
        return saved;
    }

    private Trip publishInternal(Trip trip, String theme) {
        if (theme != null && !theme.isBlank()) {
            trip.setPublishedTheme(StaticSiteRenderer.safeTheme(theme));
        }
        trip.setStatus(TripStatus.PUBLISHED);
        trip.setPublishedAt(Instant.now());
        Trip saved = trips.save(trip);
        renderer.render(saved);
        return saved;
    }

    private Optional<PublishRequest> pendingRequest(Trip trip) {
        return trip.getPublishRequests().stream().filter(PublishRequest::isPending).findFirst();
    }

    private PublishRequest require(Trip trip, String requestId) {
        return trip.getPublishRequests().stream()
                .filter(request -> request.getId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Publish request"));
    }

    private PublishRequest requirePending(Trip trip, String requestId) {
        PublishRequest request = require(trip, requestId);
        if (!request.isPending()) {
            throw ApiException.conflict("already_decided", "That request has already been decided.");
        }
        return request;
    }
}
