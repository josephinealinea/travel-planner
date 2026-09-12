package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ItineraryService {

    public record Input(String checklistItemId,
                        ChecklistCategory category,
                        String description,
                        LocalDateTime startAt,
                        LocalDateTime endAt,
                        BigDecimal cost,
                        String currency) {}

    private final ItineraryRepository itinerary;
    private final DestinationRepository destinations;
    private final ChecklistService checklistService;
    private final TripAccessService access;
    private final PlanTemplates templates;
    private final BudgetSync budgetSync;
    private final CountryCatalog countries;

    public ItineraryService(ItineraryRepository itinerary,
                            DestinationRepository destinations,
                            ChecklistService checklistService,
                            TripAccessService access,
                            PlanTemplates templates,
                            BudgetSync budgetSync,
                            CountryCatalog countries) {
        this.itinerary = itinerary;
        this.destinations = destinations;
        this.checklistService = checklistService;
        this.access = access;
        this.templates = templates;
        this.budgetSync = budgetSync;
        this.countries = countries;
    }

    public List<ItineraryItem> list(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return itinerary.findAllOrdered(trip.getSlug());
    }

    /** What the Plan form opens with for a given checklist item. */
    public PlanTemplates.Template planTemplate(String tripId, String userId, String checklistItemId) {
        Trip trip = access.requireMember(tripId, userId);
        ChecklistItem item = checklistService.require(trip, checklistItemId);

        List<Destination> all = destinations.findAll(trip.getSlug());
        Destination destination = item.getDestinationId() == null ? null
                : all.stream()
                     .filter(d -> d.getId().equals(item.getDestinationId()))
                     .findFirst()
                     .orElse(null);

        // Default the currency to the destination country's own, falling back
        // to the trip's display currency.
        String currency = destination == null ? null : countries.currencyOf(destination.getCountryCode());
        return templates.forChecklistItem(item, destination, all,
                currency == null ? trip.getDisplayCurrency() : currency);
    }

    public ItineraryItem create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireDescription(input.description());
        requireTimeOrder(input.startAt(), input.endAt());

        ItineraryItem plan = new ItineraryItem();
        plan.setId(Ids.newId());
        plan.setTripId(tripId);
        plan.setCreatedAt(Instant.now());
        plan.setSortOrder(itinerary.findAll(trip.getSlug()).size());

        // A plan usually belongs to a checklist item, but can also be added
        // straight to the itinerary.
        if (input.checklistItemId() != null && !input.checklistItemId().isBlank()) {
            ChecklistItem item = checklistService.require(trip, input.checklistItemId());
            plan.setChecklistItemId(item.getId());
            plan.setCategory(input.category() == null ? item.getCategory() : input.category());
        } else {
            plan.setCategory(input.category() == null ? ChecklistCategory.OTHERS : input.category());
        }

        plan.setDescription(input.description().trim());
        plan.setStartAt(input.startAt());
        plan.setEndAt(input.endAt());
        applyCost(plan, input.cost(), input.currency(), trip);

        itinerary.save(trip.getSlug(), plan);
        budgetSync.afterSave(trip.getSlug(), plan);
        // Saved again because the sync writes back the new budget row's id.
        return itinerary.save(trip.getSlug(), plan);
    }

    public ItineraryItem update(String tripId, String userId, String planId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem plan = require(trip, planId);

        if (input.description() != null) {
            requireDescription(input.description());
            plan.setDescription(input.description().trim());
        }
        if (input.category() != null) plan.setCategory(input.category());
        if (input.startAt() != null) plan.setStartAt(input.startAt());
        if (input.endAt() != null) plan.setEndAt(input.endAt());
        requireTimeOrder(plan.getStartAt(), plan.getEndAt());

        // A null cost in the payload means "leave it"; BigDecimal.ZERO is how
        // the client clears it, which removes the budget row.
        if (input.cost() != null) applyCost(plan, input.cost(), input.currency(), trip);
        else if (input.currency() != null && plan.hasCost()) {
            plan.setCurrency(input.currency().trim().toUpperCase());
        }

        itinerary.save(trip.getSlug(), plan);
        budgetSync.afterSave(trip.getSlug(), plan);
        return itinerary.save(trip.getSlug(), plan);
    }

    public void delete(String tripId, String userId, String planId) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem plan = require(trip, planId);

        budgetSync.afterDelete(trip.getSlug(), plan);
        itinerary.delete(trip.getSlug(), planId);
    }

    private void applyCost(ItineraryItem plan, BigDecimal cost, String currency, Trip trip) {
        if (cost == null || cost.signum() == 0) {
            plan.setCost(null);
            plan.setCurrency(null);
            return;
        }
        if (cost.signum() < 0) {
            throw ApiException.badRequest("A cost cannot be negative.");
        }
        plan.setCost(cost);
        plan.setCurrency(currency == null || currency.isBlank()
                ? trip.getDisplayCurrency()
                : currency.trim().toUpperCase());
    }

    private ItineraryItem require(Trip trip, String planId) {
        return itinerary.findById(trip.getSlug(), planId)
                .orElseThrow(() -> ApiException.notFound("Itinerary item"));
    }

    private static void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("A plan needs a description.");
        }
        if (description.trim().length() > 300) {
            throw ApiException.badRequest("That description is too long.");
        }
    }

    private static void requireTimeOrder(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The end time cannot be before the start time.");
        }
    }
}
