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
            @NotBlank(message = "{validation.plan.descriptionRequired}")
            @Size(max = 300, message = "{validation.description.tooLong}") String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            /**
             * True when the member gave a date but no time.
             *
             * Sent by the client rather than inferred here, because by the time
             * a date and an empty time field have been combined into a
             * LocalDateTime the two cases are indistinguishable: "no time
             * given" and "midnight" are both 00:00. Only the form knows which
             * it was.
             */
            Boolean allDay,
            BigDecimal cost,
            String currency,
            /** "Expense already charged" for the budget row a cost creates. */
            Boolean costCharged,
            /** "Shared by" for that row. Absent means the whole trip. */
            List<String> costSharedByUserIds,
            /** "Paid by" for that row. Absent or empty means nobody. */
            String costPaidByUserId,
            List<String> countryCodes,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True puts it back to following its checklist item. */
            Boolean inheritTravellers,
            String note) {

        ItineraryService.Input toInput() {
            return new ItineraryService.Input(
                    checklistItemId, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note);
        }
    }

    public record PatchRequest(
            ChecklistCategory category,
            @Size(max = 300, message = "{validation.description.tooLong}") String description,
            LocalDateTime startAt,
            LocalDateTime endAt,
            Boolean allDay,
            BigDecimal cost,
            String currency,
            Boolean costCharged,
            List<String> costSharedByUserIds,
            /** Absent leaves the budget row's payer as it is; an empty string clears it. */
            String costPaidByUserId,
            List<String> countryCodes,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True puts it back to following its checklist item. */
            Boolean inheritTravellers,
            String note) {

        ItineraryService.Input toInput() {
            return new ItineraryService.Input(
                    null, category, description, startAt, endAt, allDay, cost, currency,
                    costCharged, costSharedByUserIds, costPaidByUserId, countryCodes,
                    travellerIds, inheritTravellers, note);
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
    @PatchMapping("/{itemId}")
    ItineraryItem update(@PathVariable String tripId,
                         @PathVariable String itemId,
                         @Valid @RequestBody PatchRequest request) {
        return itinerary.update(tripId, currentUser.userId(), itemId, request.toInput());
    }

    /**
     * Removes a plan with every day it covers. Distinct from DELETE /{itemId},
     * which removes one day: from a checklist item a plan is the booking, not
     * one of its nights.
     */
    @DeleteMapping("/{itemId}/plan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlan(@PathVariable String tripId, @PathVariable String itemId) {
        itinerary.deletePlan(tripId, currentUser.userId(), itemId);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId, @PathVariable String itemId) {
        itinerary.delete(tripId, currentUser.userId(), itemId);
    }
}
