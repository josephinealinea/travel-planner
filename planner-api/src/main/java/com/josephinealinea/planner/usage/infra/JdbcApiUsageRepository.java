package com.josephinealinea.planner.usage.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code api_usage}. The check and the increment are one statement: the
 * {@code WHERE} on the conflict branch stops the update once the cap is reached,
 * and the row count says whether the call was granted, so two racing callers
 * cannot both take the last one.
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcApiUsageRepository implements ApiUsageRepository {

    private final JdbcClient jdbc;

    public JdbcApiUsageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquire(String service, String month, int cap) {
        if (cap <= 0) return false;
        return jdbc.sql("""
                        INSERT INTO api_usage (service, month, calls) VALUES (:service, :month, 1)
                        ON CONFLICT (service, month) DO UPDATE SET calls = api_usage.calls + 1
                        WHERE api_usage.calls < :cap
                        """)
                .param("service", service)
                .param("month", month)
                .param("cap", cap)
                .update() == 1;
    }

    @Override
    public int calls(String service, String month) {
        return jdbc.sql("SELECT calls FROM api_usage WHERE service = :service AND month = :month")
                .param("service", service)
                .param("month", month)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
