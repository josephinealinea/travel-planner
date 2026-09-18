package com.josephinealinea.planner.checklist.infra;

import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.storage.TripScopedRepository;

import java.util.Comparator;
import java.util.List;

/**
 * The storage contract for checklist items: {@link YamlChecklistRepository}
 * with the database flag off, a JDBC implementation with it on. Services
 * depend on this interface only.
 *
 * {@link #findAllOrdered} is a default method so its ordering is written once
 * for both stores — see BudgetRepository for why.
 */
public interface ChecklistRepository extends TripScopedRepository<ChecklistItem> {

    /**
     * TODO first, then completed — the same ordering the Jekyll travel layout
     * applies when it renders the public checklist.
     */
    default List<ChecklistItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator.comparing(ChecklistItem::isCompleted)
                        .thenComparingInt(ChecklistItem::getSortOrder))
                .toList();
    }
}
