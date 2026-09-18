package com.josephinealinea.planner.storage.jdbc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway owns the schema and only the schema: no migration inserts, updates or
 * deletes a row. Data reaches the database through the application — the
 * one-off YAML import included — where it is validated and can be tested,
 * never through a script that runs once on somebody's production database.
 *
 * Checked by reading the files rather than by convention, and needing no
 * Docker, so it runs everywhere. Comments are stripped first, and only
 * statements are inspected, so {@code ON DELETE CASCADE} in a table
 * definition is not mistaken for a DELETE.
 */
class MigrationsAreSchemaOnlyTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    /** A statement that changes rows, wherever it starts. */
    private static final Pattern WRITES_ROWS = Pattern.compile(
            "^(WITH\\b.*\\b)?(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|COPY)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void thereIsAtLeastTheBaseline() throws IOException {
        assertThat(migrations()).extracting(p -> p.getFileName().toString())
                .contains("V1__baseline_schema.sql");
    }

    @Test
    void noMigrationWritesARow() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : migrations()) {
            String sql = Files.readString(file)
                    .replaceAll("(?s)/\\*.*?\\*/", " ")
                    .replaceAll("--[^\\n]*", " ");
            for (String statement : sql.split(";")) {
                String trimmed = statement.strip();
                if (WRITES_ROWS.matcher(trimmed).find()) {
                    offenders.add(file.getFileName() + ": " + trimmed.lines().findFirst().orElse(""));
                }
            }
        }
        assertThat(offenders).as("row-changing statements under db/migration").isEmpty();
    }

    private static List<Path> migrations() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.filter(f -> f.toString().endsWith(".sql")).sorted().toList();
        }
    }
}
