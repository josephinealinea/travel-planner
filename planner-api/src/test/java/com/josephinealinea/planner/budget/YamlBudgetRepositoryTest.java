package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.destinations.PerTripStores;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The budget contract against YAML. */
class YamlBudgetRepositoryTest extends BudgetRepositoryContract {

    private YamlBudgetRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlBudgetRepository(new YamlStore(), PerTripStores.yamlPaths(dir), new TripLocks());
    }

    @Override
    protected BudgetRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
