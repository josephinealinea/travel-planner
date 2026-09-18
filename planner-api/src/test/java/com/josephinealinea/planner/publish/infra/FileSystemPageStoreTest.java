package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.publish.infra.PageStore.Area;
import com.josephinealinea.planner.publish.infra.PageStore.PageFile;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The contract against plain files, plus the on-disk layout the CDN copy relies on. */
class FileSystemPageStoreTest extends PageStoreContract {

    private PageStore store;
    private Path dir;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
        store = new FileSystemPageStore(new YamlStore(), new YamlPaths(props));
    }

    @Override
    PageStore store() {
        return store;
    }

    /**
     * The same paths as before the store existed: the published directory is
     * still a static site to copy as-is, and staging is still its sibling.
     */
    @Test
    void keepsTheDirectoryLayout() {
        store.write(Area.PUBLISHED, SLUG, null, PageFile.INDEX, "trip");
        store.write(Area.PUBLISHED, SLUG, "sam", PageFile.DATA, "{}");
        store.write(Area.PENDING, SLUG, null, PageFile.INDEX, "staged");

        assertThat(dir.resolve("published/latam/index.html")).hasContent("trip");
        assertThat(dir.resolve("published/latam/m/sam/trip.json")).hasContent("{}");
        assertThat(dir.resolve("published-pending/latam/index.html")).hasContent("staged");
    }
}
