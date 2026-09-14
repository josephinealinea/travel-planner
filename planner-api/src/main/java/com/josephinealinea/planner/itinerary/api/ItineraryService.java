package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.api.TripWindow;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ItineraryService {

    public record Input(String checklistItemId,
                        ChecklistCategory category,
                        String description,
                        LocalDateTime startAt,
                        LocalDateTime endAt,
                        /** A date with no time. Null on a patch means "leave it". */
                        Boolean allDay,
                        BigDecimal cost,
                        String currency,
                        List<String> countryCodes) {}

    private final ItineraryRepository itinerary;
    private final DestinationRepository destinations;
    private final ChecklistService checklistService;
    private final TripAccessService access;
    private final PlanTemplates templates;
    private final BudgetSync budgetSync;
    private final CountryCatalog countries;
    private final TripCountries tripCountries;

    public ItineraryService(ItineraryRepository itinerary,
                            DestinationRepository destinations,
                            ChecklistService checklistService,
                            TripAccessService access,
                            PlanTemplates templates,
                            BudgetSync budgetSync,
                            CountryCatalog countries,
                            TripCountries tripCountries) {
        this.itinerary = itinerary;
        this.destinations = destinations;
        this.checklistService = checklistService;
        this.access = access;
        this.templates = templates;
        this.budgetSync = budgetSync;
        this.countries = countries;
        this.tripCountries = tripCountries;
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

        // The pre-fill still needs a city: it suggests a description naming the
        // place and, for a stay, that place's own check-in and check-out dates
        // — none of which a country can answer. A seeded item remembers which
        // destination produced it, which is exactly the city meant. For an item
        // added by hand there is no such city, so the first destination in a
        // country it links to stands in. Every field stays editable either way.
        Destination destination = all.stream()
                .filter(d -> d.getId().equals(item.getSeededFromDestinationId()))
                .findFirst()
                .or(() -> item.getCountryCodes().stream()
                        .flatMap(code -> all.stream().filter(d -> code.equalsIgnoreCase(d.getCountryCode())))
                        .findFirst())
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
        requireWithinTrip(trip, input);

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
        // A date with no time belongs to a day rather than an hour, which is
        // the same thing a stay's middle nights say — and the same flag says
        // it, so both render as "—" with no second rule anywhere.
        if (Boolean.TRUE.equals(input.allDay())) plan.setAllDay(true);
        plan.setCountryCodes(tripCountries.validate(trip, input.countryCodes()));
        applyCost(plan, input.cost(), input.currency(), trip);
        Audit.created(plan, userId);

        itinerary.save(trip.getSlug(), plan);
        budgetSync.afterSave(trip.getSlug(), plan, userId);
        // Saved again because the sync writes back the new budget row's id.
        itinerary.save(trip.getSlug(), plan);

        spreadPlanOverItsDays(trip, userId, plan);
        return plan;
    }

    /**
     * Writes the rest of a plan out as its own entries, one per day.
     *
     * You are in the room every day from check-in to check-out, and an
     * itinerary that lists the hotel on the arrival day alone shows nothing at
     * all for the nights between. Those days are written as real entries at
     * create time rather than conjured while rendering, so each one is an
     * ordinary item: selectable on its own, editable on its own, and deletable
     * on its own without touching the rest of the stay.
     *
     * Only the check-in entry keeps the cost and the budget back-link — one
     * booking is one charge, and repeating it would read as four — and only it
     * has no planId, so it alone counts as a plan against its checklist item.
     * The nights between carry no time either: they belong to a day, not to an
     * hour. The check-out day keeps the hour you have to be out by.
     *
     * Deliberately create-time only. Editing a night afterwards edits that
     * night, and moving one entry's dates does not re-spread the stay: once
     * these exist they are ordinary entries, and quietly rewriting the others
     * would undo edits somebody made on purpose.
     */
    private void spreadPlanOverItsDays(Trip trip, String userId, ItineraryItem checkIn) {
        if (checkIn.getCategory() != ChecklistCategory.LODGING) return;
        if (checkIn.getStartAt() == null || checkIn.getEndAt() == null) return;

        LocalDate first = checkIn.getStartAt().toLocalDate();
        LocalDate checkout = checkIn.getEndAt().toLocalDate();
        if (!checkout.isAfter(first)) return;

        // The check-in entry now describes only its own day; the hour you have
        // to be out by moves to the day it actually falls on.
        LocalDateTime checkoutAt = checkIn.getEndAt();
        checkIn.setEndAt(null);
        itinerary.save(trip.getSlug(), checkIn);

        int nextSortOrder = itinerary.findAll(trip.getSlug()).size();
        for (LocalDate day = first.plusDays(1); !day.isAfter(checkout); day = day.plusDays(1)) {
            boolean isCheckout = day.equals(checkout);
            ItineraryItem night = new ItineraryItem();
            night.setId(Ids.newId());
            night.setTripId(checkIn.getTripId());
            night.setCreatedAt(Instant.now());
            night.setSortOrder(nextSortOrder++);
            night.setChecklistItemId(checkIn.getChecklistItemId());
            // Points back at the plan's own row, so anything counting plans
            // counts bookings and anything listing days counts days.
            night.setPlanId(checkIn.getId());
            night.setCategory(checkIn.getCategory());
            night.setDescription(checkIn.getDescription());
            night.setCountryCodes(new ArrayList<>(checkIn.getCountryCodes()));
            night.setStartAt(isCheckout ? checkoutAt : day.atStartOfDay());
            night.setAllDay(isCheckout ? null : Boolean.TRUE);
            Audit.created(night, userId);
            // No cost, so BudgetSync leaves these alone entirely.
            itinerary.save(trip.getSlug(), night);
        }
    }

    public ItineraryItem update(String tripId, String userId, String itemId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem plan = require(trip, itemId);

        if (input.description() != null) {
            requireDescription(input.description());
            plan.setDescription(input.description().trim());
        }
        if (input.category() != null) plan.setCategory(input.category());
        if (input.startAt() != null) plan.setStartAt(input.startAt());
        if (input.endAt() != null) plan.setEndAt(input.endAt());
        // Null leaves it alone; false is how adding a time clears it again.
        if (input.allDay() != null) plan.setAllDay(input.allDay() ? true : null);
        requireTimeOrder(plan.getStartAt(), plan.getEndAt());
        requireWithinTrip(trip, input);
        // An empty list is how the client clears every link; null leaves it alone.
        if (input.countryCodes() != null) {
            plan.setCountryCodes(tripCountries.validate(trip, input.countryCodes()));
        }

        // A null cost in the payload means "leave it"; BigDecimal.ZERO is how
        // the client clears it, which removes the budget row.
        if (input.cost() != null) applyCost(plan, input.cost(), input.currency(), trip);
        else if (input.currency() != null && plan.hasCost()) {
            plan.setCurrency(input.currency().trim().toUpperCase());
        }
        Audit.touched(plan, userId);

        itinerary.save(trip.getSlug(), plan);
        budgetSync.afterSave(trip.getSlug(), plan, userId);
        return itinerary.save(trip.getSlug(), plan);
    }

    /** Removes one entry — one day of a plan, not the whole plan. */
    public void delete(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem plan = require(trip, itemId);

        budgetSync.afterDelete(trip.getSlug(), plan);
        itinerary.delete(trip.getSlug(), itemId);
        adoptOrphanedDays(trip, userId, plan);
    }

    /**
     * Keeps a plan owned after its owning row is deleted.
     *
     * Every group has exactly one row with no planId, and that row is what
     * makes the plan count as one plan against its checklist item. Delete it
     * and the remaining days would point at nothing: present on the itinerary,
     * invisible to anything counting plans. The earliest survivor takes over
     * instead, and the rest re-point at it.
     *
     * The cost is not carried over. It belonged to the row that was deleted,
     * and BudgetSync has already removed the charge it created — inventing a
     * new owner for a charge nobody entered there would be worse than losing
     * it visibly.
     */
    private void adoptOrphanedDays(Trip trip, String userId, ItineraryItem deleted) {
        if (!deleted.ownsItsPlan()) return;

        List<ItineraryItem> all = new ArrayList<>(itinerary.findAll(trip.getSlug()));
        List<ItineraryItem> orphans = all.stream()
                .filter(item -> deleted.getId().equals(item.getPlanId()))
                .sorted(Comparator.comparing(ItineraryItem::getStartAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (orphans.isEmpty()) return;

        ItineraryItem heir = orphans.get(0);
        heir.setPlanId(null);
        Audit.touched(heir, userId);
        orphans.stream().skip(1).forEach(item -> {
            item.setPlanId(heir.getId());
            Audit.touched(item, userId);
        });
        itinerary.replaceAll(trip.getSlug(), all);
    }

    /**
     * Removes a plan and every day it covers.
     *
     * This is what "delete this plan" means from a checklist item, where the
     * plan is the booking rather than one of its nights. Deleting the days one
     * at a time through the same path keeps the budget cascade on the route it
     * already takes for a single delete.
     */
    public int deletePlan(String tripId, String userId, String planRowId) {
        Trip trip = access.requireMember(tripId, userId);
        ItineraryItem plan = require(trip, planRowId);

        List<String> doomed = new ArrayList<>();
        doomed.add(plan.getId());
        itinerary.findAll(trip.getSlug()).stream()
                .filter(item -> plan.getId().equals(item.getPlanId()))
                .map(ItineraryItem::getId)
                .forEach(doomed::add);

        for (String id : doomed) {
            itinerary.findById(trip.getSlug(), id).ifPresent(item -> {
                budgetSync.afterDelete(trip.getSlug(), item);
                itinerary.delete(trip.getSlug(), id);
            });
        }
        return doomed.size();
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

    private ItineraryItem require(Trip trip, String itemId) {
        return itinerary.findById(trip.getSlug(), itemId)
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

    /**
     * Only the times actually sent are checked, so an older plan that predates
     * a change to the trip's own dates stays editable.
     */
    private static void requireWithinTrip(Trip trip, Input input) {
        TripWindow window = TripWindow.of(trip);
        window.require(input.startAt(), "start date");
        window.require(input.endAt(), "end date");
    }

    private static void requireTimeOrder(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest("The end time cannot be before the start time.");
        }
    }
}
