package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.flights.api.FlightStatusService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The refresh icon on a published page. No sign-in: a published page has none.
 * Reaches the API through the existing {@code /api/*} forwarding Function, on the
 * page's own origin. {@code no-cache}: the page caches the reply itself for the
 * TTL the body carries, so the HTTP cache must not add a second, hidden clock.
 */
@RestController
@RequestMapping("/api/v1/public/trips/{slug}/flights")
public class PublicFlightController {

    private final FlightStatusService status;

    public PublicFlightController(FlightStatusService status) {
        this.status = status;
    }

    @GetMapping
    ResponseEntity<PublicFlightView> flight(@PathVariable String slug,
                                                    @RequestParam String number,
                                                    @RequestParam LocalDate date) {
        return status.status(slug, number, date)
                .map(view -> ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(PublicFlightView.from(view)))
                .orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noCache()).build());
    }
}
