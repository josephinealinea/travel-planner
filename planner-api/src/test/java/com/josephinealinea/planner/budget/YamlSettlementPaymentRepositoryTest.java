package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.infra.SettlementPaymentRepository;
import com.josephinealinea.planner.budget.infra.YamlSettlementPaymentRepository;
import com.josephinealinea.planner.storage.TestYamlPaths;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The settlement-payment contract against YAML. */
class YamlSettlementPaymentRepositoryTest extends SettlementPaymentRepositoryContract {

    private YamlSettlementPaymentRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlSettlementPaymentRepository(
                new YamlStore(), TestYamlPaths.under(dir), new TripLocks());
    }

    @Override
    protected SettlementPaymentRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
