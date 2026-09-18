package com.josephinealinea.planner.storage.jdbc;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.List;

/**
 * Real PostgreSQL for a JDBC test: {@code postgres:17-alpine}, the version
 * Neon runs, migrated by the application's own Flyway scripts. Used through
 * {@link PostgresTest} (or {@link PostgresTestBase}), not directly.
 *
 * It is a JUnit extension rather than only a base class so that a test which
 * already extends something — a repository contract, above all — can still
 * have a database: annotate it {@code @PostgresTest} and read the statics.
 *
 * <ul>
 *   <li><b>One container for the whole run</b>, started by the first class
 *       that needs it and left to Testcontainers' reaper to stop. A fresh
 *       database per class would cost seconds each for nothing.</li>
 *   <li><b>Every table is emptied before each test</b>, identities reset, so
 *       tests cannot see each other's rows and {@code seq} starts from 1.
 *       Tables a test creates for itself are emptied too. The Flyway history
 *       is kept; the schema is never rebuilt.</li>
 * </ul>
 */
public final class PostgresTestDatabase implements BeforeAllCallback, BeforeEachCallback {

    public static final String IMAGE = "postgres:17-alpine";

    private static PostgreSQLContainer<?> container;
    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static PlatformTransactionManager transactionManager;

    @Override
    public void beforeAll(ExtensionContext context) {
        start();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        emptyEveryTable();
    }

    public static synchronized void start() {
        if (dataSource != null) return;
        container = new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("planner")
                .withUsername("planner")
                .withPassword("planner");
        container.start();
        dataSource = new DriverManagerDataSource(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    public static void emptyEveryTable() {
        List<String> tables = jdbc().sql("""
                        SELECT tablename FROM pg_tables
                        WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                        """)
                .query(String.class)
                .list();
        if (tables.isEmpty()) return;
        jdbc().sql("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").update();
    }

    /**
     * A trip row with only what the schema requires. Per-trip tables resolve
     * a slug through {@code trips}, so nothing can be stored until this exists.
     */
    public static void givenTrip(String id, String slug) {
        jdbc().sql("INSERT INTO trips (id, slug) VALUES (:id, :slug)")
                .param("id", id)
                .param("slug", slug)
                .update();
    }

    public static PostgreSQLContainer<?> container() {
        return started(container);
    }

    public static DataSource dataSource() {
        return started(dataSource);
    }

    public static JdbcClient jdbc() {
        return started(jdbc);
    }

    public static PlatformTransactionManager transactionManager() {
        return started(transactionManager);
    }

    private static <V> V started(V value) {
        if (value == null) {
            throw new IllegalStateException("PostgreSQL is not started — annotate the test class @PostgresTest");
        }
        return value;
    }
}
