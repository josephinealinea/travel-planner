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
import java.util.List;

/** Budget rows as one YAML list per trip: travels/budget/&lt;slug&gt;.yml. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlBudgetRepository extends TripScopedYamlRepository<BudgetItem> implements BudgetRepository {

    public YamlBudgetRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<BudgetItem>>() {}, BudgetItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.budget(tripSlug);
    }
}
