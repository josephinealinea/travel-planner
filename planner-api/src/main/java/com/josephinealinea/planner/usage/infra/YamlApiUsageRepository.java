package com.josephinealinea.planner.usage.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.usage.domain.ApiUsageRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * One file for the install, {@code data/api-usage.yml}: a list of
 * {@link ApiUsageRow}. {@code synchronized} is the lock, which is enough because
 * YAML mode is single-instance only, like every YAML store here.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlApiUsageRepository implements ApiUsageRepository {

    private static final TypeReference<List<ApiUsageRow>> ROWS = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;

    public YamlApiUsageRepository(YamlStore store, YamlPaths paths) {
        this.store = store;
        this.paths = paths;
    }

    @Override
    public synchronized boolean tryAcquire(String service, String month, int cap) {
        if (cap <= 0) return false;
        List<ApiUsageRow> rows = store.readList(paths.apiUsage(), ROWS);
        for (int i = 0; i < rows.size(); i++) {
            ApiUsageRow row = rows.get(i);
            if (row.service().equals(service) && row.month().equals(month)) {
                if (row.calls() >= cap) return false;
                rows.set(i, new ApiUsageRow(service, month, row.calls() + 1));
                store.write(paths.apiUsage(), rows);
                return true;
            }
        }
        rows.add(new ApiUsageRow(service, month, 1));
        store.write(paths.apiUsage(), rows);
        return true;
    }

    @Override
    public synchronized int calls(String service, String month) {
        return store.readList(paths.apiUsage(), ROWS).stream()
                .filter(row -> row.service().equals(service) && row.month().equals(month))
                .mapToInt(ApiUsageRow::calls)
                .findFirst()
                .orElse(0);
    }
}
