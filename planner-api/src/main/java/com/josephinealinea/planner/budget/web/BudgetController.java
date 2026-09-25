package com.josephinealinea.planner.budget.web;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.config.CurrentUserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/budget")
public class BudgetController {

    public record CreateRequest(
            @NotBlank(message = "{validation.expense.descriptionRequired}") String description,
            @NotNull(message = "{validation.category.required}") ChecklistCategory category,
            @NotNull(message = "{validation.amount.required}") BigDecimal amount,
            String currency,
            LocalDate date,
            List<String> countryCodes,
            /** "Shared by" — member user ids. Absent means the whole trip. */
            List<String> sharedByUserIds,
            /** "Paid by" — one member user id. Absent or empty means nobody. */
            String paidByUserId,
            /** "Expense already charged". Absent means charged — see BudgetService.Input. */
            Boolean charged,
            String note) {

        BudgetService.Input toInput() {
            return new BudgetService.Input(description, category, amount, currency, date,
                    countryCodes, sharedByUserIds, paidByUserId, charged, note);
        }
    }

    public record PatchRequest(
            String description,
            ChecklistCategory category,
            BigDecimal amount,
            String currency,
            LocalDate date,
            List<String> countryCodes,
            List<String> sharedByUserIds,
            /** Absent leaves the payer as it is; an empty string clears it. */
            String paidByUserId,
            /** Absent leaves the status as it is, like every other field here. */
            Boolean charged,
            String note) {

        BudgetService.Input toInput() {
            return new BudgetService.Input(description, category, amount, currency, date,
                    countryCodes, sharedByUserIds, paidByUserId, charged, note);
        }
    }

    private final BudgetService budget;
    private final CurrentUserContext currentUser;

    public BudgetController(BudgetService budget, CurrentUserContext currentUser) {
        this.budget = budget;
        this.currentUser = currentUser;
    }

    /** Rows plus the per-category rollup and the display-currency total. */
    @GetMapping
    BudgetService.Summary summary(@PathVariable String tripId) {
        return budget.summarise(tripId, currentUser.userId());
    }

    /** A manual expense, with no plan behind it. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    BudgetItem create(@PathVariable String tripId, @Valid @RequestBody CreateRequest request) {
        return budget.create(tripId, currentUser.userId(), request.toInput());
    }

    @PatchMapping("/{itemId}")
    BudgetItem update(@PathVariable String tripId,
                      @PathVariable String itemId,
                      @Valid @RequestBody PatchRequest request) {
        return budget.update(tripId, currentUser.userId(), itemId, request.toInput());
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String tripId, @PathVariable String itemId) {
        budget.delete(tripId, currentUser.userId(), itemId);
    }
}
