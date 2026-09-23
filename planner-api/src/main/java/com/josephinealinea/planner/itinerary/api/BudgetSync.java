package com.josephinealinea.planner.itinerary.api;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.shared.Audit;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;


/**
 * Keeps the budget in step with plan costs. The sync is deliberately one-way
 * and narrow, so the numbers stay predictable:
 *
 *  - a cost on a plan with no budget row yet creates one, copying category,
 *    amount, currency, description, note, date and countryCodes;
 *  - a later cost or currency change updates only the amount and currency —
 *    plus `status`, `sharedByUserIds` and `paidByUserId`, and only when the
 *    form actually sends them — so a description, category or set of
 *    locations somebody has since corrected in the budget is never clobbered;
 *  - the row it creates starts <b>pending</b>, not charged: a plan is
 *    something you intend to do, and its cost is money still to leave. That is
 *    the opposite default from an expense typed into the budget by hand, and
 *    it is why the Plan form's own "Expense already charged" box starts
 *    unticked. Whoever adds the plan can tick it there, or later in the
 *    budget;
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
     *
     * paidByUserId has three meanings rather than two: null when the form did
     * not send it (leave the row alone), an empty string when it sent a blank
     * (clear it), or an already-validated member id (set it).
     */
    public void afterSave(String tripSlug, ItineraryItem plan, String userId,
                          Boolean charged, java.util.List<String> sharedByUserIds,
                          String paidByUserId) {
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
            created.setNote(plan.getNote());
            created.setAmount(plan.getCost());
            created.setCurrency(plan.getCurrency());
            created.setDate(plan.getStartAt() == null ? null : plan.getStartAt().toLocalDate());
            created.setCountryCodes(plan.getCountryCodes());
            // Pending unless the form said otherwise — the reverse of a manual
            // expense's default. See the class comment.
            created.markCharged(Boolean.TRUE.equals(charged), Instant.now());
            if (sharedByUserIds != null) created.setSharedByUserIds(sharedByUserIds);
            if (paidByUserId != null && !paidByUserId.isBlank()) created.setPaidByUserId(paidByUserId);
            // A plan is the other way into a budget row, so the rule has to
            // hold here too — enforced only on the budget's own form it would
            // be wide open from the Plan and itinerary forms.
            BudgetService.requirePayerWhenCharged(created);
            Audit.created(created, userId);
            budget.save(tripSlug, created);
            plan.setBudgetItemId(created.getId());
        } else {
            existing.setAmount(plan.getCost());
            existing.setCurrency(plan.getCurrency());
            // The one field beyond amount and currency a plan edit may touch,
            // and only when the form actually sent it. The Plan form shows the
            // linked row's real status, so submitting it is the member saying
            // what that status should be — not this sync deciding for them.
            if (charged != null) existing.markCharged(charged, Instant.now());
            // Same reasoning as the status beside it: the Plan form shows the
            // row's real "Shared by", so submitting it is the member saying who
            // shares the cost — not this sync deciding for them.
            if (sharedByUserIds != null) existing.setSharedByUserIds(sharedByUserIds);
            // And again for who paid: absent leaves it, blank clears it.
            if (paidByUserId != null) {
                existing.setPaidByUserId(paidByUserId.isBlank() ? null : paidByUserId);
            }
            BudgetService.requirePayerWhenCharged(existing);
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
