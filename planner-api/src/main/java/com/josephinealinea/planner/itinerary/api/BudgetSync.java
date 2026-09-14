package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.stereotype.Component;


/**
 * Keeps the budget in step with plan costs. The sync is deliberately one-way
 * and narrow, so the numbers stay predictable:
 *
 *  - a cost on a plan with no budget row yet creates one, copying category,
 *    amount, currency, description, date and countryCodes;
 *  - a later cost or currency change updates only the amount and currency, so
 *    a description, category or set of locations somebody has since corrected
 *    in the budget is never clobbered;
 *  - clearing the cost, or deleting the plan, removes the row it created;
 *  - a manually added expense has no plan behind it and is never touched.
 */
@Component
public class BudgetSync {

    private final BudgetRepository budget;

    public BudgetSync(BudgetRepository budget) {
        this.budget = budget;
    }

    /**
     * Called after every itinerary create and update. The userId is the member
     * whose plan produced the charge: a row this creates was never typed into
     * the budget by anybody, so without it the only expenses on the trip with
     * no author would be exactly the automatic ones.
     */
    public void afterSave(String tripSlug, ItineraryItem plan, String userId) {
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
            created.setCountryCodes(plan.getCountryCodes());
            Audit.created(created, userId);
            budget.save(tripSlug, created);
            plan.setBudgetItemId(created.getId());
        } else {
            existing.setAmount(plan.getCost());
            existing.setCurrency(plan.getCurrency());
            Audit.touched(existing, userId);
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
