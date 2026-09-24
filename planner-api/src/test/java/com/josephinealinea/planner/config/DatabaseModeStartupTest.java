package com.josephinealinea.planner.config;

import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatabaseModeEnvironment at boot, in both modes: the same auto-configuration
 * — everything Boot would configure from this classpath, application.yml
 * included — started with the flag off and with it on.
 *
 * It boots a bare {@code @EnableAutoConfiguration} rather than the whole
 * application, because with the flag on the application also needs the JDBC
 * repositories, which do not exist yet. What is under test here is only the
 * switch: off builds no DataSource at all, on builds one and lets Flyway
 * migrate the database on startup. YamlModeStartupTest covers the whole
 * application in YAML mode.
 *
 * The flag is passed as a command-line argument on purpose: that outranks
 * application.yml's {@code ${FEATURE_ENABLE_DATABASE:false}}, where a
 * "default property" would not.
 */
@PostgresTest
class DatabaseModeStartupTest {

    @EnableAutoConfiguration
    static class AutoConfigurationOnly {
    }

    @Test
    void flagOffConfiguresNoDatabase() {
        try (ConfigurableApplicationContext context = boot(
                "--feature-enable-database=false",
                // Would be connected to if anything still tried; nothing may.
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/nothing")) {
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
            assertThat(context.getBeanNamesForType(JdbcClient.class)).isEmpty();
            assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).isEmpty();
        }
    }

    @Test
    void flagOnConfiguresTheDatabaseAndMigratesIt() {
        JdbcClient admin = PostgresTestDatabase.jdbc();
        admin.sql("DROP DATABASE IF EXISTS bootcheck").update();
        admin.sql("CREATE DATABASE bootcheck").update();
        var pg = PostgresTestDatabase.container();

        try (ConfigurableApplicationContext context = boot(
                "--feature-enable-database=true",
                "--spring.datasource.url=" + pg.getJdbcUrl().replace("/planner", "/bootcheck"),
                "--spring.datasource.username=" + pg.getUsername(),
                "--spring.datasource.password=" + pg.getPassword())) {
            assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).hasSize(1);

            JdbcClient jdbc = context.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                    .query(String.class).list()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
            assertThat(jdbc.sql("SELECT count(*) FROM trips").query(Long.class).single()).isZero();
        }
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(AutoConfigurationOnly.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
