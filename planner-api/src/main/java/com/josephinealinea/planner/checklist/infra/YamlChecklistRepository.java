package com.josephinealinea.planner.checklist.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.TripScopedYamlRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.List;

/** Checklist items as one YAML list per trip: travels/checklist/&lt;slug&gt;.yml. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlChecklistRepository extends TripScopedYamlRepository<ChecklistItem> implements ChecklistRepository {

    public YamlChecklistRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<ChecklistItem>>() {}, ChecklistItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.checklist(tripSlug);
    }
}
