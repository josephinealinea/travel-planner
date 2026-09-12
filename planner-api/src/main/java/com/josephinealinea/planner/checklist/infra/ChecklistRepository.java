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
import java.util.Comparator;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class ChecklistRepository extends TripScopedYamlRepository<ChecklistItem> {

    public ChecklistRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        super(store, paths, locks, new TypeReference<List<ChecklistItem>>() {}, ChecklistItem::getId);
    }

    @Override
    protected Path fileFor(String tripSlug) {
        return paths.checklist(tripSlug);
    }

    /**
     * TODO first, then completed — the same ordering the Jekyll travel layout
     * applies when it renders the public checklist.
     */
    public List<ChecklistItem> findAllOrdered(String tripSlug) {
        return findAll(tripSlug).stream()
                .sorted(Comparator.comparing(ChecklistItem::isCompleted)
                        .thenComparingInt(ChecklistItem::getSortOrder))
                .toList();
    }
}
