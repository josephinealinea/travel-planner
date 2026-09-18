package com.josephinealinea.planner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads "feature-enable-database" and says, once at startup, which store is in
 * use — the one line worth finding in a log when records seem to be missing.
 *
 * It used to refuse to start with the flag on, because no database
 * implementation existed and a flag that silently did nothing was worse than
 * one that failed. The flag now chooses between two implementations of every
 * repository interface: the YAML ones carry
 * {@code @ConditionalOnProperty(havingValue = "false", matchIfMissing = true)}
 * and the JDBC ones {@code havingValue = "true"}. It also decides whether a
 * DataSource is configured at all — see {@link DatabaseModeEnvironment}.
 */
@Component
public class FeatureFlags implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);

    private final boolean databaseEnabled;

    public FeatureFlags(@Value("${feature-enable-database:false}") boolean databaseEnabled) {
        this.databaseEnabled = databaseEnabled;
    }

    public boolean databaseEnabled() {
        return databaseEnabled;
    }

    @Override
    public void afterPropertiesSet() {
        if (databaseEnabled) {
            log.info("Storage: PostgreSQL (feature-enable-database=true)");
        } else {
            log.info("Storage: YAML file store (feature-enable-database=false)");
        }
    }
}
