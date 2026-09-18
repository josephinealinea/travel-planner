package com.josephinealinea.planner.budget.infra;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.storage.TripScopedRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The storage contract for budget rows: {@link YamlBudgetRepository} with the
 * database flag off, a JDBC implementation with it on. Services depend on this
 * interface only.
 *
 * The finders are default methods over {@link #findAll} on purpose. Their
 * ordering is a rule about budgets, not about storage — date first, undated
 * last, and otherwise insertion order, which the stable sort keeps — so
 * writing it once means the two stores cannot disagree about it. An
 * implementation may override one for speed, but then owns proving it still
 * orders the same way.
 */
public interface BudgetRepository extends TripScopedRepository<BudgetItem> {

    default List<BudgetItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator.comparing(BudgetItem::getDate,
                        Comparator.<LocalDate>nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    default Optional<BudgetItem> findByItineraryItem(String tripSlug, String itineraryItemId) {
        return findAll(tripSlug).stream()
                .filter(item -> itineraryItemId.equals(item.getItineraryItemId()))
                .findFirst();
    }
}
