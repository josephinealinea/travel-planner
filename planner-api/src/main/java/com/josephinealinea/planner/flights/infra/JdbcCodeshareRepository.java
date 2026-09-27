// JdbcCodeshareRepository.java
package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** {@code codeshare_mappings}. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcCodeshareRepository implements CodeshareRepository {

    private final JdbcClient jdbc;

    public JdbcCodeshareRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> operatingFor(String bookedNumber) {
        return jdbc.sql("SELECT operating_number FROM codeshare_mappings WHERE booked_number = :b")
                .param("b", bookedNumber)
                .query(String.class)
                .optional();
    }

    @Override
    public void save(CodeshareMapping mapping) {
        jdbc.sql("""
                        INSERT INTO codeshare_mappings (booked_number, operating_number, resolved_at)
                        VALUES (:b, :o, :at)
                        ON CONFLICT (booked_number) DO UPDATE SET
                            operating_number = EXCLUDED.operating_number, resolved_at = EXCLUDED.resolved_at
                        """)
                .param("b", mapping.bookedNumber())
                .param("o", mapping.operatingNumber())
                .param("at", JdbcValues.timestamptz(mapping.resolvedAt()))
                .update();
    }

    @Override
    public List<CodeshareMapping> findAll() {
        return jdbc.sql("SELECT * FROM codeshare_mappings ORDER BY booked_number")
                .query((rs, i) -> new CodeshareMapping(rs.getString("booked_number"),
                        rs.getString("operating_number"), JdbcValues.instant(rs, "resolved_at")))
                .list();
    }
}
