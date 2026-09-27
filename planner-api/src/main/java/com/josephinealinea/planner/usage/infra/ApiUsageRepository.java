package com.josephinealinea.planner.usage.infra;

/**
 * Calls per external service per month: {@link YamlApiUsageRepository} with the
 * database flag off, {@link JdbcApiUsageRepository} with it on.
 *
 * Not trip-scoped, and not specific to flights: another API is counted by
 * naming another service.
 */
public interface ApiUsageRepository {

    /**
     * Records one call if fewer than {@code cap} have been made in
     * {@code month}. True means the call may go out. Checking and counting are
     * one operation so two callers cannot both take the last call.
     */
    boolean tryAcquire(String service, String month, int cap);

    /** Calls made so far; zero for a service or month never seen. */
    int calls(String service, String month);
}
