package com.josephinealinea.planner.destinations.web;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.domain.Destination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/destinations")
public class DestinationController {

    /** Coordinates are optional and always accepted as sent. */
    public record DestinationRequest(
            @NotBlank(message = "Give the destination a name")
            @Size(max = 120, message = "That name is too long") String name,
            String countryCode,
            String countryName,
            Double latitude,
            Double longitude,
            Long geonameId,
            String timezone,
            LocalDate startDate,
            LocalDate endDate,
            String note,
            Boolean suppressChecklist,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True clears it back to "not set". */
            Boolean inheritTravellers) {

        DestinationService.Input toInput() {
            return new DestinationService.Input(name, countryCode, countryName, latitude,
                    longitude, geonameId, timezone, startDate, endDate, note,
                    suppressChecklist, travellerIds, inheritTravellers);
        }
    }

    /** Same fields, but nothing is required when editing. */
    public record PatchDestinationRequest(
            @Size(max = 120, message = "That name is too long") String name,
            String countryCode,
            String countryName,
            Double latitude,
            Double longitude,
            Long geonameId,
            String timezone,
            LocalDate startDate,
            LocalDate endDate,
            String note,
            Boolean suppressChecklist,
            /** Who's going; absent leaves it alone, [] is the whole trip. See Travellers. */
            List<String> travellerIds,
            /** True clears it back to "not set". */
            Boolean inheritTravellers) {

        DestinationService.Input toInput() {
            return new DestinationService.Input(name, countryCode, countryName, latitude,
                    longitude, geonameId, timezone, startDate, endDate, note,
                    suppressChecklist, travellerIds, inheritTravellers);
        }
    }

    /** Reports the checklist items seeded alongside the new destination. */
    public record CreatedResponse(Destination destination, List<ChecklistItem> seededChecklist) {}

    public record ReorderRequest(List<String> orderedIds) {}

    private final DestinationService destinations;
    private final CurrentUserContext currentUser;

    public DestinationController(DestinationService destinations, CurrentUserContext currentUser) {
        this.destinations = destinations;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<Destination> list(@PathVariable String tripId) {
        return destinations.list(tripId, currentUser.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedResponse create(@PathVariable String tripId,
                           @Valid @RequestBody DestinationRequest request) {
        var created = destinations.create(tripId, currentUser.userId(), request.toInput());
        return new CreatedResponse(created.destination(), created.seededChecklist());
    }

    @PatchMapping("/{destinationId}")
    Destination update(@PathVariable String tripId,
                       @PathVariable String destinationId,
                       @Valid @RequestBody PatchDestinationRequest request) {
        return destinations.update(tripId, currentUser.userId(), destinationId, request.toInput());
    }

    /** Checklist items, itinerary entries and budget rows for this destination are kept and unlinked, not deleted. */
    @DeleteMapping("/{destinationId}")
    DeleteResponse delete(@PathVariable String tripId, @PathVariable String destinationId) {
        DestinationService.UnlinkResult result =
                destinations.delete(tripId, currentUser.userId(), destinationId);
        return new DeleteResponse(result.checklistItemsUnlinked(), result.itineraryItemsUnlinked(),
                result.budgetItemsUnlinked());
    }

    public record DeleteResponse(int checklistItemsUnlinked, int itineraryItemsUnlinked, int budgetItemsUnlinked) {}

    @PostMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reorder(@PathVariable String tripId, @RequestBody ReorderRequest request) {
        destinations.reorder(tripId, currentUser.userId(), request.orderedIds());
    }
}
