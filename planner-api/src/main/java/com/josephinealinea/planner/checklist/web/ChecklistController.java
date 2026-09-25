package com.josephinealinea.planner.checklist.web;

import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/checklist")
public class ChecklistController {

    public record CreateRequest(
            @NotNull(message = "{validation.category.required}") ChecklistCategory category,
            @NotBlank(message = "{validation.checklist.descriptionRequired}")
            @Size(max = 300, message = "{validation.description.tooLong}") String description,
            String note,
            List<String> countryCodes,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True puts it back to following its destination. */
            Boolean inheritTravellers) {

        ChecklistService.Input toInput() {
            return new ChecklistService.Input(category, description, note, countryCodes,
                    travellerIds, inheritTravellers);
        }
    }

    public record PatchRequest(
            ChecklistCategory category,
            @Size(max = 300, message = "{validation.description.tooLong}") String description,
            String note,
            List<String> countryCodes,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True puts it back to following its destination. */
            Boolean inheritTravellers) {

        ChecklistService.Input toInput() {
            return new ChecklistService.Input(category, description, note, countryCodes,
                    travellerIds, inheritTravellers);
        }
    }

    public record StatusRequest(@NotNull(message = "{validation.status.required}") ChecklistStatus status) {}

    private final ChecklistService checklist;
    private final ItineraryService itinerary;
    private final CurrentUserContext currentUser;

    public ChecklistController(ChecklistService checklist,
                               ItineraryService itinerary,
                               CurrentUserContext currentUser) {
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<ChecklistItem> list(@PathVariable String tripId) {
        return checklist.list(tripId, currentUser.userId());
    }

    /** Works whether or not the trip has any destinations. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ChecklistItem create(@PathVariable String tripId, @Valid @RequestBody CreateRequest request) {
        return checklist.create(tripId, currentUser.userId(), request.toInput());
    }

    @PatchMapping("/{itemId}")
    ChecklistItem update(@PathVariable String tripId,
                         @PathVariable String itemId,
                         @Valid @RequestBody PatchRequest request) {
        return checklist.update(tripId, currentUser.userId(), itemId, request.toInput());
    }

    /**
     * The only route to COMPLETED. Adding a plan does not complete an item, so
     * a member can record several plans first and decide when it is done.
     */
    @PatchMapping("/{itemId}/status")
    ChecklistItem setStatus(@PathVariable String tripId,
                            @PathVariable String itemId,
                            @Valid @RequestBody StatusRequest request) {
        return checklist.setStatus(tripId, currentUser.userId(), itemId, request.status());
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId, @PathVariable String itemId) {
        checklist.delete(tripId, currentUser.userId(), itemId);
    }

    /** What the Plan form opens pre-filled with. */
    @GetMapping("/{itemId}/plan-template")
    PlanTemplates.Template planTemplate(@PathVariable String tripId, @PathVariable String itemId) {
        return itinerary.planTemplate(tripId, currentUser.userId(), itemId);
    }
}
