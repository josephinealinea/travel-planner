package com.josephinealinea.planner.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps YAML mode starting exactly as it did before the JDBC starter was a
 * dependency.
 *
 * With {@code spring-boot-starter-jdbc} and Flyway on the classpath, Spring
 * Boot builds a DataSource on every start — and in YAML mode, where nothing
 * configures one, that dies with "url not specified", or worse, quietly
 * connects to whatever {@code spring.datasource.url} happens to default to.
 * So when {@code feature-enable-database} is off, the database
 * auto-configurations are switched off with it, by adding them to
 * {@code spring.autoconfigure.exclude}.
 *
 * <b>Merged, never replaced.</b> An exclude list somebody already set — in
 * application.yml, an environment variable or a test — is read first and kept;
 * this only adds to it. Overwriting the property would silently re-enable
 * whatever they had turned off.
 *
 * This has to be an EnvironmentPostProcessor rather than a
 * {@code @Conditional} on a configuration class: which auto-configurations run
 * is decided from the environment before any bean exists, and the exclude
 * property is the one input to that decision we can set. It runs after the
 * config files are loaded (lowest precedence among post-processors), so it
 * sees the flag from application.yml, the environment and the command line
 * alike.
 *
 * Registered in {@code META-INF/spring.factories}.
 */
public class DatabaseModeEnvironment implements EnvironmentPostProcessor, Ordered {

    static final String FLAG = "feature-enable-database";
    static final String EXCLUDE = "spring.autoconfigure.exclude";
    static final String SOURCE_NAME = "databaseModeExcludes";

    /** Everything that would build or use a DataSource. */
    static final List<String> DATABASE_AUTO_CONFIGURATIONS = List.of(
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration",
            "org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration",
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getProperty(FLAG, Boolean.class, false)) return;

        // The Binder reads both shapes a list can arrive in — a comma-separated
        // string and indexed keys (exclude[0], exclude[1]) — which is also how
        // AutoConfigurationImportSelector reads it back.
        Set<String> excludes = new LinkedHashSet<>(Binder.get(environment)
                .bind(EXCLUDE, Bindable.listOf(String.class))
                .orElse(List.of()));
        excludes.addAll(DATABASE_AUTO_CONFIGURATIONS);

        // First in precedence, so this merged value is the one that is read.
        environment.getPropertySources().addFirst(
                new MapPropertySource(SOURCE_NAME, Map.of(EXCLUDE, String.join(",", excludes))));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
