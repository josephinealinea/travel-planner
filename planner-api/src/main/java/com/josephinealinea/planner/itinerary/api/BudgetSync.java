package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Keeps the budget in step with plan costs. The sync is deliberately one-way
 * and narrow, so the numbers stay predictable:
 *
 *  - a cost on a plan with no budget row yet creates one, copying category,
 *    amount, currency, description and date;
 *  - a later cost or currency change updates only the amount and currency, so
 *    a description or category somebody has since corrected in the budget is
 *    never clobbered;
 *  - clearing the cost, or deleting the plan, removes the row it created;
 *  - a manually added expense has no plan behind it and is never touched.
 */
@Component
public class BudgetSync {

    private final BudgetRepository budget;

    public BudgetSync(BudgetRepository budget) {
        this.budget = budget;
    }

    /** Called after every itinerary create and update. */
    public void afterSave(String tripSlug, ItineraryItem plan) {
        if (!plan.hasCost()) {
            removeLinked(tripSlug, plan);
            return;
        }

        BudgetItem existing = plan.getBudgetItemId() == null
                ? null
                : budget.findById(tripSlug, plan.getBudgetItemId()).orElse(null);

        if (existing == null) {
            BudgetItem created = new BudgetItem();
            created.setId(Ids.newId());
            created.setTripId(plan.getTripId());
            created.setItineraryItemId(plan.getId());
            created.setCategory(plan.getCategory());
            created.setDescription(plan.getDescription());
            created.setAmount(plan.getCost());
            created.setCurrency(plan.getCurrency());
            created.setDate(plan.getStartAt() == null ? null : plan.getStartAt().toLocalDate());
            created.setCreatedAt(Instant.now());
            budget.save(tripSlug, created);
            plan.setBudgetItemId(created.getId());
        } else {
            existing.setAmount(plan.getCost());
            existing.setCurrency(plan.getCurrency());
            budget.save(tripSlug, existing);
        }
    }

    /** Called when a plan is deleted. */
    public void afterDelete(String tripSlug, ItineraryItem plan) {
        removeLinked(tripSlug, plan);
    }

    private void removeLinked(String tripSlug, ItineraryItem plan) {
        if (plan.getBudgetItemId() != null) {
            budget.delete(tripSlug, plan.getBudgetItemId());
            plan.setBudgetItemId(null);
        }
        // Also covers a row linked to this plan whose back-link was lost.
        budget.findByItineraryItem(tripSlug, plan.getId())
                .ifPresent(orphan -> budget.delete(tripSlug, orphan.getId()));
    }
}
