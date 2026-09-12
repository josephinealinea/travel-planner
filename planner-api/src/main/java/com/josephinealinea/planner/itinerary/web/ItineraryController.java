package com.josephinealinea.planner.itinerary.web;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/itinerary")
public class ItineraryController {

    public record CreateRequest(
            String checklistItemId,
            ChecklistCategory category,
            @NotBlank(message = "Describe the plan")
            @Size(max = 300, message = "That description is too long") String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            BigDecimal cost,
            String currency) {

        ItineraryService.Input toInput() {
            return new ItineraryService.Input(
                    checklistItemId, category, description, startAt, endAt, cost, currency);
        }
    }

    public record PatchRequest(
            ChecklistCategory category,
            @Size(max = 300, message = "That description is too long") String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            BigDecimal cost,
            String currency) {

        ItineraryService.Input toInput() {
            return new ItineraryService.Input(
                    null, category, description, startAt, endAt, cost, currency);
        }
    }

    private final ItineraryService itinerary;
    private final CurrentUserContext currentUser;

    public ItineraryController(ItineraryService itinerary, CurrentUserContext currentUser) {
        this.itinerary = itinerary;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<ItineraryItem> list(@PathVariable String tripId) {
        return itinerary.list(tripId, currentUser.userId());
    }

    /** A cost here is what creates the matching budget record. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ItineraryItem create(@PathVariable String tripId, @Valid @RequestBody CreateRequest request) {
        return itinerary.create(tripId, currentUser.userId(), request.toInput());
    }

    /** Sending cost as 0 clears it, which removes the budget record it created. */
    @PatchMapping("/{planId}")
    ItineraryItem update(@PathVariable String tripId,
                         @PathVariable String planId,
                         @Valid @RequestBody PatchRequest request) {
        return itinerary.update(tripId, currentUser.userId(), planId, request.toInput());
    }

    @DeleteMapping("/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId, @PathVariable String planId) {
        itinerary.delete(tripId, currentUser.userId(), planId);
    }
}
