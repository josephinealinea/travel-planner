package com.josephinealinea.planner.storage.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * The convenient form of {@link PostgresTest} for a test with no other
 * superclass: the database's handles as plain methods. A test that must
 * extend something else — a repository contract — uses the annotation and
 * {@link PostgresTestDatabase}'s statics instead.
 */
@PostgresTest
public abstract class PostgresTestBase {

    protected static JdbcClient jdbc() {
        return PostgresTestDatabase.jdbc();
    }

    protected static DataSource dataSource() {
        return PostgresTestDatabase.dataSource();
    }

    protected static PlatformTransactionManager transactionManager() {
        return PostgresTestDatabase.transactionManager();
    }

    protected static void givenTrip(String id, String slug) {
        PostgresTestDatabase.givenTrip(id, slug);
    }
}
