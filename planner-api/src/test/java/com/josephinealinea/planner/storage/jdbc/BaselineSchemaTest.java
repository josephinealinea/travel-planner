package com.josephinealinea.planner.storage.jdbc;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 against a real, empty PostgreSQL 17 — the version Neon runs — and the
 * column types that would quietly change behaviour if they were wrong.
 */
class BaselineSchemaTest extends PostgresTestBase {

    @Test
    void v1AppliesCleanlyToAnEmptyDatabase() {
        jdbc().sql("DROP DATABASE IF EXISTS fresh").update();
        jdbc().sql("CREATE DATABASE fresh").update();
        var pg = PostgresTestDatabase.container();
        DataSource fresh = new DriverManagerDataSource(
                pg.getJdbcUrl().replace("/planner", "/fresh"), pg.getUsername(), pg.getPassword());

        MigrateResult result = Flyway.configure()
                .dataSource(fresh)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        // Every migration applies to an empty database, in order, ending at the latest.
        assertThat(result.targetSchemaVersion).isEqualTo("2");
        assertThat(JdbcClient.create(fresh).sql("SELECT count(*) FROM trips").query(Long.class).single())
                .as("schema only: a migration never writes a row").isZero();
    }

    @Test
    void itineraryTimesAreWallClockWithNoTimeZone() {
        // A 06:00 departure in Cusco must not become 11:00 because the server runs in UTC.
        assertThat(dataType("itinerary_items", "start_at")).isEqualTo("timestamp without time zone");
        assertThat(dataType("itinerary_items", "end_at")).isEqualTo("timestamp without time zone");
    }

    @Test
    void auditStampsAreInstants() {
        assertThat(dataType("budget_items", "created_at")).isEqualTo("timestamp with time zone");
    }

    @Test
    void sharersAreAnOrderedTextArray() {
        assertThat(dataType("budget_items", "shared_by_user_ids")).isEqualTo("ARRAY");
        assertThat(jdbc().sql("""
                        SELECT udt_name FROM information_schema.columns
                        WHERE table_name = 'budget_items' AND column_name = 'shared_by_user_ids'
                        """).query(String.class).single())
                .isEqualTo("_text");
    }

    /**
     * Nullable with no default, unlike shared_by_user_ids: NULL is "not set,
     * follow the parent" and '{}' is "explicitly the whole trip". A default of
     * '{}' would quietly turn every "same as Cusco" into "everyone".
     */
    @Test
    void travellerColumnsAreNullableTextArraysWithNoDefault() {
        for (String table : new String[] {"destinations", "checklist_items", "itinerary_items"}) {
            var column = jdbc().sql("""
                            SELECT udt_name, is_nullable, column_default FROM information_schema.columns
                            WHERE table_name = :table AND column_name = 'traveller_ids'
                            """).param("table", table)
                    .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)})
                    .single();
            assertThat(column[0]).as(table).isEqualTo("_text");
            assertThat(column[1]).as(table).isEqualTo("YES");
            assertThat(column[2]).as(table).isNull();
        }
    }

    @Test
    void aBudgetRowWithNoStatusIsACharge() {
        // The YAML rule, restated for the database: no status reads as CONFIRMED.
        givenTrip("t1", "lima");
        jdbc().sql("INSERT INTO budget_items (trip_id, id) VALUES ('t1', 'b1')").update();

        assertThat(jdbc().sql("SELECT status FROM budget_items WHERE id = 'b1'").query(String.class).single())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void everyPerTripTableIsKeyedByTripAndIdAndCarriesSeq() {
        for (String table : new String[] {"destinations", "checklist_items", "itinerary_items",
                "budget_items", "weather_records"}) {
            assertThat(jdbc().sql("""
                            SELECT string_agg(a.attname, ',' ORDER BY a.attnum)
                            FROM pg_index i
                            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                            WHERE i.indrelid = CAST(:table AS regclass) AND i.indisprimary
                            """).param("table", table).query(String.class).single())
                    .as(table + " primary key").isEqualTo("trip_id,id");
            assertThat(dataType(table, "seq")).as(table + ".seq").isEqualTo("bigint");
        }
    }

    private String dataType(String table, String column) {
        return jdbc().sql("""
                        SELECT data_type FROM information_schema.columns
                        WHERE table_name = :table AND column_name = :column
                        """)
                .param("table", table)
                .param("column", column)
                .query(String.class)
                .single();
    }
}
