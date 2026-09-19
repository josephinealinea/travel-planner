package com.josephinealinea.planner.checklist;

import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.storage.TestYamlPaths;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/** The checklist contract against YAML. */
class YamlChecklistRepositoryTest extends ChecklistRepositoryContract {

    private YamlChecklistRepository repository;

    @BeforeEach
    void store(@TempDir Path dir) {
        repository = new YamlChecklistRepository(new YamlStore(), TestYamlPaths.under(dir), new TripLocks());
    }

    @Override
    protected ChecklistRepository store() {
        return repository;
    }

    @Override
    protected void givenTrip(String slug) {
        // A YAML trip needs nothing on disk before its first write.
    }
}
