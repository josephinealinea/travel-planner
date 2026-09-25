package com.josephinealinea.planner.checklist.api;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.Travellers;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChecklistService {

    public record Input(ChecklistCategory category,
                        String description,
                        String note,
                        List<String> countryCodes,
                        /** Who's going. Null leaves it alone; [] is the whole trip. See Travellers. */
                        List<String> travellerIds,
                        /** True clears it back to "not set", i.e. following its destination. */
                        Boolean inheritTravellers) {

        /** The shape from before travellers existed: says nothing about them. */
        public Input(ChecklistCategory category, String description, String note, List<String> countryCodes) {
            this(category, description, note, countryCodes, null, null);
        }
    }

    private final ChecklistRepository checklist;
    private final ItineraryRepository itinerary;
    private final TripAccessService access;
    private final TripCountries countries;

    public ChecklistService(ChecklistRepository checklist,
                            ItineraryRepository itinerary,
                            TripAccessService access,
                            TripCountries countries) {
        this.checklist = checklist;
        this.itinerary = itinerary;
        this.access = access;
        this.countries = countries;
    }

    public List<ChecklistItem> list(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return checklist.findAllOrdered(trip.getSlug());
    }

    /** Every item on the trip, unordered — for callers that resolve travellers. */
    public List<ChecklistItem> all(Trip trip) {
        return checklist.findAll(trip.getSlug());
    }

    /** Checklist items can be added whether or not any destination exists. */
    public ChecklistItem create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireDescription(input.description());

        ChecklistItem item = new ChecklistItem();
        item.setId(Ids.newId());
        item.setTripId(tripId);
        item.setCategory(input.category() == null ? ChecklistCategory.OTHERS : input.category());
        item.setDescription(input.description().trim());
        item.setNote(blankToNull(input.note()));
        item.setStatus(ChecklistStatus.TODO);
        item.setSortOrder(checklist.findAll(trip.getSlug()).size());
        item.setCountryCodes(countries.validate(trip, input.countryCodes()));
        item.setTravellerIds(Travellers.change(trip, null, input.travellerIds(), input.inheritTravellers()));
        Audit.created(item, userId);

        return checklist.save(trip.getSlug(), item);
    }

    public ChecklistItem update(String tripId, String userId, String itemId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        ChecklistItem item = require(trip, itemId);

        if (input.description() != null) {
            requireDescription(input.description());
            item.setDescription(input.description().trim());
        }
        if (input.note() != null) item.setNote(blankToNull(input.note()));
        if (input.category() != null) item.setCategory(input.category());
        if (input.countryCodes() != null) {
            // An empty list is how the client clears every link.
            item.setCountryCodes(countries.validate(trip, input.countryCodes()));
        }
        item.setTravellerIds(Travellers.change(trip, item.getTravellerIds(),
                input.travellerIds(), input.inheritTravellers()));
        Audit.touched(item, userId);
        return checklist.save(trip.getSlug(), item);
    }

    /**
     * The only way an item becomes COMPLETED. Adding a plan deliberately does
     * not complete it — that is what "Plan another" is for, so several plans can
     * be recorded before the member decides the job is done. Completing with no
     * plan at all is allowed, which is the "force it complete" case.
     */
    public ChecklistItem setStatus(String tripId, String userId, String itemId, ChecklistStatus status) {
        Trip trip = access.requireMember(tripId, userId);
        ChecklistItem item = require(trip, itemId);

        item.setStatus(status);
        item.setCompletedAt(status == ChecklistStatus.COMPLETED ? Instant.now() : null);
        Audit.touched(item, userId);
        return checklist.save(trip.getSlug(), item);
    }

    /** Deleting an item unlinks its plans; the itinerary entries themselves stay. */
    public void delete(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        require(trip, itemId);

        checklist.delete(trip.getSlug(), itemId);

        var plans = new ArrayList<>(itinerary.findAll(trip.getSlug()));
        boolean touched = false;
        for (var plan : plans) {
            if (itemId.equals(plan.getChecklistItemId())) {
                plan.setChecklistItemId(null);
                Audit.touched(plan, userId);
                touched = true;
            }
        }
        if (touched) itinerary.replaceAll(trip.getSlug(), plans);
    }

    public ChecklistItem require(Trip trip, String itemId) {
        return checklist.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("error.checklistItem.notFound"));
    }

    /** Validates every id against this trip's destinations, de-duplicated, order preserved. */

    private static void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("error.checklist.descriptionRequired");
        }
        if (description.trim().length() > 300) {
            throw ApiException.badRequest("error.description.tooLong");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
