package com.josephinealinea.planner.budget.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Service
public class BudgetService {

    public record Input(String description,
                        ChecklistCategory category,
                        BigDecimal amount,
                        String currency,
                        LocalDate date) {}

    /**
     * The rollup. Currencies with no configured rate are reported separately
     * rather than silently dropped or counted at 1:1, so a missing rate is
     * visible instead of quietly wrong.
     */
    public record Summary(List<BudgetItem> items,
                          String displayCurrency,
                          Map<String, BigDecimal> byCategory,
                          BigDecimal total,
                          List<String> currenciesMissingRates) {}

    private final BudgetRepository budget;
    private final ItineraryRepository itinerary;
    private final TripAccessService access;

    public BudgetService(BudgetRepository budget,
                         ItineraryRepository itinerary,
                         TripAccessService access) {
        this.budget = budget;
        this.itinerary = itinerary;
        this.access = access;
    }

    public Summary summarise(String tripId, String userId) {
        Trip trip = access.requireMember(tripId, userId);
        return summarise(trip);
    }

    /** Also used by the publish renderer, which already holds the trip. */
    public Summary summarise(Trip trip) {
        List<BudgetItem> items = budget.findAllOrdered(trip.getSlug());
        String display = trip.getDisplayCurrency();

        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        for (ChecklistCategory category : ChecklistCategory.values()) {
            byCategory.put(category.name(), BigDecimal.ZERO);
        }

        BigDecimal total = BigDecimal.ZERO;
        var missing = new TreeSet<String>();

        for (BudgetItem item : items) {
            BigDecimal converted = convert(item, display, trip.getExchangeRates());
            if (converted == null) {
                missing.add(item.getCurrency());
                continue;
            }
            String key = item.getCategory().name();
            byCategory.merge(key, converted, BigDecimal::add);
            total = total.add(converted);
        }

        return new Summary(items, display, byCategory,
                total.setScale(2, RoundingMode.HALF_UP), new ArrayList<>(missing));
    }

    /**
     * Rates are hand-maintained per trip, keyed by currency code, expressed as
     * units of that currency per one unit of the display currency — the same
     * convention as the exchange_rates block in the Jekyll site's budget YAML.
     */
    public static BigDecimal convert(BudgetItem item, String displayCurrency, Map<String, BigDecimal> rates) {
        if (item.getAmount() == null) return BigDecimal.ZERO;
        String currency = item.getCurrency();
        if (currency == null || currency.equalsIgnoreCase(displayCurrency)) {
            return item.getAmount();
        }
        BigDecimal rate = rates.get(currency.toUpperCase());
        if (rate == null || rate.signum() == 0) return null;
        return item.getAmount().divide(rate, 2, RoundingMode.HALF_UP);
    }

    /** A manual expense — no plan behind it, so BudgetSync leaves it alone. */
    public BudgetItem create(String tripId, String userId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        requireDescription(input.description());
        requireAmount(input.amount());

        BudgetItem item = new BudgetItem();
        item.setId(Ids.newId());
        item.setTripId(tripId);
        item.setCategory(input.category() == null ? ChecklistCategory.OTHERS : input.category());
        item.setDescription(input.description().trim());
        item.setAmount(input.amount());
        item.setCurrency(input.currency() == null || input.currency().isBlank()
                ? trip.getDisplayCurrency()
                : input.currency().trim().toUpperCase());
        item.setDate(input.date());
        item.setCreatedAt(Instant.now());

        return budget.save(trip.getSlug(), item);
    }

    /** Any member can change the amount, currency, category and description. */
    public BudgetItem update(String tripId, String userId, String itemId, Input input) {
        Trip trip = access.requireMember(tripId, userId);
        BudgetItem item = budget.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("Budget item"));

        if (input.description() != null) {
            requireDescription(input.description());
            item.setDescription(input.description().trim());
        }
        if (input.category() != null) item.setCategory(input.category());
        if (input.amount() != null) {
            requireAmount(input.amount());
            item.setAmount(input.amount());
        }
        if (input.currency() != null && !input.currency().isBlank()) {
            item.setCurrency(input.currency().trim().toUpperCase());
        }
        if (input.date() != null) item.setDate(input.date());

        BudgetItem saved = budget.save(trip.getSlug(), item);

        // If this row came from a plan, push the amount back so the plan's cost
        // and the budget do not disagree.
        if (saved.isFromPlan() && input.amount() != null) {
            itinerary.findById(trip.getSlug(), saved.getItineraryItemId()).ifPresent(plan -> {
                plan.setCost(saved.getAmount());
                plan.setCurrency(saved.getCurrency());
                itinerary.save(trip.getSlug(), plan);
            });
        }
        return saved;
    }

    /** Deleting a budget row clears the originating plan's back-link. */
    public void delete(String tripId, String userId, String itemId) {
        Trip trip = access.requireMember(tripId, userId);
        BudgetItem item = budget.findById(trip.getSlug(), itemId)
                .orElseThrow(() -> ApiException.notFound("Budget item"));

        budget.delete(trip.getSlug(), itemId);

        if (item.isFromPlan()) {
            List<ItineraryItem> plans = new ArrayList<>(itinerary.findAll(trip.getSlug()));
            boolean touched = false;
            for (ItineraryItem plan : plans) {
                if (itemId.equals(plan.getBudgetItemId())) {
                    plan.setBudgetItemId(null);
                    plan.setCost(null);
                    plan.setCurrency(null);
                    touched = true;
                }
            }
            if (touched) itinerary.replaceAll(trip.getSlug(), plans);
        }
    }

    private static void requireDescription(String description) {
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("An expense needs a description.");
        }
    }

    private static void requireAmount(BigDecimal amount) {
        if (amount == null) throw ApiException.badRequest("An expense needs an amount.");
        if (amount.signum() < 0) throw ApiException.badRequest("An amount cannot be negative.");
    }
}
