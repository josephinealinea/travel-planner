package com.josephinealinea.planner.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which auto-configurations the flag switches off, and that an exclude list
 * somebody already set survives it. The boot-level proof — a context that
 * really does or does not build a DataSource — is DatabaseModeStartupTest.
 */
class DatabaseModeEnvironmentTest {

    private static final String SOMETHING_ELSE =
            "org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration";

    private final DatabaseModeEnvironment processor = new DatabaseModeEnvironment();

    @Test
    void flagOffExcludesEveryDatabaseAutoConfiguration() {
        MockEnvironment env = new MockEnvironment().withProperty("feature-enable-database", "false");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).containsExactlyInAnyOrderElementsOf(
                DatabaseModeEnvironment.DATABASE_AUTO_CONFIGURATIONS);
        assertThat(excludes(env)).contains(
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");
    }

    @Test
    void anAbsentFlagMeansOff() {
        MockEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).containsAll(DatabaseModeEnvironment.DATABASE_AUTO_CONFIGURATIONS);
    }

    @Test
    void flagOnExcludesNothing() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("feature-enable-database", "true")
                .withProperty("spring.autoconfigure.exclude", SOMETHING_ELSE);

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).containsExactly(SOMETHING_ELSE);
        assertThat(env.getPropertySources().contains(DatabaseModeEnvironment.SOURCE_NAME)).isFalse();
    }

    @Test
    void anExistingCommaSeparatedExcludeIsKept() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.autoconfigure.exclude", SOMETHING_ELSE + ",com.example.Other");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).startsWith(SOMETHING_ELSE, "com.example.Other")
                .containsAll(DatabaseModeEnvironment.DATABASE_AUTO_CONFIGURATIONS);
    }

    @Test
    void anExistingIndexedExcludeIsKept() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.autoconfigure.exclude[0]", SOMETHING_ELSE)
                .withProperty("spring.autoconfigure.exclude[1]", "com.example.Other");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).startsWith(SOMETHING_ELSE, "com.example.Other")
                .containsAll(DatabaseModeEnvironment.DATABASE_AUTO_CONFIGURATIONS);
    }

    @Test
    void anExcludeAlreadyNamingADatabaseOneIsNotDuplicated() {
        String dataSource = DatabaseModeEnvironment.DATABASE_AUTO_CONFIGURATIONS.get(0);
        MockEnvironment env = new MockEnvironment().withProperty("spring.autoconfigure.exclude", dataSource);

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).doesNotHaveDuplicates();
    }

    @Test
    void theFlagIsReadThroughPlaceholders() {
        // application.yml says ${FEATURE_ENABLE_DATABASE:false}.
        MockEnvironment env = new MockEnvironment()
                .withProperty("FEATURE_ENABLE_DATABASE", "true")
                .withProperty("feature-enable-database", "${FEATURE_ENABLE_DATABASE:false}");

        processor.postProcessEnvironment(env, new SpringApplication());

        assertThat(excludes(env)).isEmpty();
    }

    /** Read back the way AutoConfigurationImportSelector reads it. */
    private static List<String> excludes(MockEnvironment env) {
        return Binder.get(env)
                .bind("spring.autoconfigure.exclude", Bindable.listOf(String.class))
                .orElse(List.of());
    }
}
