package com.josephinealinea.planner.storage.jdbc;

import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a test class against the shared, Flyway-migrated PostgreSQL of
 * {@link PostgresTestDatabase}, with every table emptied before each test.
 *
 * <b>Skipped, not failed, on a machine without Docker</b>
 * ({@code disabledWithoutDocker}): that condition is evaluated before the
 * database is started, so no container is ever attempted there, and every
 * YAML test still runs.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(PostgresTestDatabase.class)
public @interface PostgresTest {
}
