package com.josephinealinea.planner.publish.web;

import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.publish.api.PublishService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.api.TripViews;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.shared.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trips/{tripId}")
public class PublishController {

    public record PublishRequestBody(String theme) {}
    /**
     * The theme travels with the request because the page is built then, in the
     * requesting member's own theme — which lives in their browser, so only
     * they can tell us what it is.
     */
    public record RequestPublishBody(String note, String theme) {}

    private final PublishService publish;
    private final TripViewAssembler views;
    private final CurrentUserContext currentUser;

    public PublishController(PublishService publish,
                             TripViewAssembler views,
                             CurrentUserContext currentUser) {
        this.publish = publish;
        this.views = views;
        this.currentUser = currentUser;
    }

    /** Owner only. Also the re-publish action for an already published trip. */
    @PostMapping("/publish")
    TripViews.PublishView publish(@PathVariable String tripId,
                                  @RequestBody(required = false) PublishRequestBody body) {
        Trip trip = publish.publish(tripId, currentUser.userId(),
                body == null ? null : body.theme());
        return views.publish(trip);
    }

    @DeleteMapping("/publish")
    TripViews.PublishView unpublish(@PathVariable String tripId) {
        return views.publish(publish.unpublish(tripId, currentUser.userId()));
    }

    /** What a non-owner member gets instead of a Publish button. */
    @PostMapping("/publish-requests")
    TripViews.PublishView request(@PathVariable String tripId,
                                  @RequestBody(required = false) RequestPublishBody body) {
        Trip trip = publish.requestPublish(tripId, currentUser.userId(),
                body == null ? null : body.note(),
                body == null ? null : body.theme());
        return views.publish(trip);
    }

    /**
     * The page a pending request built, for trip members only.
     *
     * The whole point of building it at request time: the owner can see
     * exactly what would go public before deciding. Served from here rather
     * than from /p/{slug} so it stays behind the session — nothing unapproved
     * is ever in the public directory, let alone reachable without signing in.
     */
    @GetMapping(value = "/publish/preview", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> preview(@PathVariable String tripId) {
        String html = publish.previewPending(tripId, currentUser.userId());
        if (html == null) throw ApiException.notFound("Staged page");
        return ResponseEntity.ok()
                // Never cached: a request can be re-sent, and a stale preview
                // would show the owner something they are not approving.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }

    @GetMapping("/publish-requests")
    TripViews.PublishView requests(@PathVariable String tripId) {
        return views.publish(publish.publishView(tripId, currentUser.userId()));
    }

    @PostMapping("/publish-requests/{requestId}/cancel")
    TripViews.PublishView cancel(@PathVariable String tripId, @PathVariable String requestId) {
        return views.publish(publish.cancelRequest(tripId, currentUser.userId(), requestId));
    }

    /** Owner only — approving is what actually publishes. */
    @PostMapping("/publish-requests/{requestId}/approve")
    TripViews.PublishView approve(@PathVariable String tripId,
                                  @PathVariable String requestId,
                                  @RequestBody(required = false) PublishRequestBody body) {
        Trip trip = publish.approveRequest(tripId, currentUser.userId(), requestId,
                body == null ? null : body.theme());
        return views.publish(trip);
    }

    @PostMapping("/publish-requests/{requestId}/reject")
    TripViews.PublishView reject(@PathVariable String tripId, @PathVariable String requestId) {
        return views.publish(publish.rejectRequest(tripId, currentUser.userId(), requestId));
    }
}
