package com.josephinealinea.planner.budget.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class BudgetRepository extends TripScopedYamlRepository<BudgetItem> {

    public BudgetRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<BudgetItem>>() {}, BudgetItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.budget(tripSlug);
    }

    public List<BudgetItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator.comparing(BudgetItem::getDate,
                        Comparator.<LocalDate>nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public Optional<BudgetItem> findByItineraryItem(String tripSlug, String itineraryItemId) {
        return findAll(tripSlug).stream()
                .filter(item -> itineraryItemId.equals(item.getItineraryItemId()))
                .findFirst();
    }
}
