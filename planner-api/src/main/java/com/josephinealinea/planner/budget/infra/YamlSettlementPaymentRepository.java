package com.josephinealinea.planner.budget.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.budget.domain.SettlementPayment;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/** Settlement payments as one YAML list per trip: travels/settlements/&lt;slug&gt;.yml. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlSettlementPaymentRepository extends TripScopedYamlRepository<SettlementPayment>
        implements SettlementPaymentRepository {

    public YamlSettlementPaymentRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<SettlementPayment>>() {}, SettlementPayment::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.settlements(tripSlug);
    }
}
