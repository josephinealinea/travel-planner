package com.josephinealinea.planner.publish.infra;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app.publish.store picks exactly one PageStore, and a filesystem install —
 * which is every install that has not asked for R2 — needs no R2 keys at all.
 */
class PageStoreSelectionTest {

    private static final AppProperties PROPS = new AppProperties(
            new AppProperties.Storage("build/unused"),
            new AppProperties.Publish("build/unused/published", "http://localhost:8080/p"),
            new AppProperties.Mail(null, null),
            new AppProperties.Security(null, null, false),
            new AppProperties.Cors(null),
            new AppProperties.Geocoding(null, null, 0, 0),
            new AppProperties.Weather(null, null, null, null, 0, 0, null),
            new AppProperties.Rates(null, null, "0 0 0 * * *", null),
            new AppProperties.Bootstrap(null, null),
            new AppProperties.Currencies(null, null, null));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(YamlStore.class)
            .withBean(YamlPaths.class, () -> new YamlPaths(PROPS))
            .withUserConfiguration(R2PageStoreConfig.class, FileSystemPageStore.class,
                    R2PageStore.class);

    @Test
    void filesystemWhenNothingIsSet() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PageStore.class);
            assertThat(context).getBean(PageStore.class).isInstanceOf(FileSystemPageStore.class);
            assertThat(context).doesNotHaveBean(R2Properties.class);
        });
    }

    @Test
    void filesystemWhenAskedFor() {
        runner.withPropertyValues("app.publish.store=filesystem").run(context ->
                assertThat(context).getBean(PageStore.class).isInstanceOf(FileSystemPageStore.class));
    }

    @Test
    void r2WhenAskedFor() {
        runner.withPropertyValues(
                "app.publish.store=r2",
                "app.r2.account-id=abc123",
                "app.r2.bucket=pages",
                "app.r2.access-key-id=key",
                "app.r2.secret-access-key=secret").run(context -> {
            assertThat(context).hasSingleBean(PageStore.class);
            assertThat(context).getBean(PageStore.class).isInstanceOf(R2PageStore.class);
            assertThat(context.getBean(R2Properties.class).endpoint())
                    .hasToString("https://abc123.r2.cloudflarestorage.com");
        });
    }

    /** Loudly at startup, naming the keys — not on somebody's first publish. */
    @Test
    void r2WithMissingKeysRefusesToStart() {
        runner.withPropertyValues("app.publish.store=r2", "app.r2.bucket=pages",
                "app.r2.account-id=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining("app.r2.account-id")
                    .hasMessageContaining("app.r2.access-key-id")
                    .hasMessageContaining("app.r2.secret-access-key")
                    .message().doesNotContain("app.r2.bucket");
        });
    }
}
