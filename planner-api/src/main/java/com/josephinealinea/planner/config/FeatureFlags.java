package com.josephinealinea.planner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guards "feature-enable-database". The flag exists so the storage layer can be
 * swapped for JPA later, but no JPA implementation is written yet — so rather
 * than let the flag silently do nothing, startup fails with a clear message.
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
            throw new IllegalStateException("""
                    feature-enable-database=true, but no database-backed repositories are \
                    implemented yet. Each module's infra/ package holds the repository \
                    interface and its YAML implementation; add a JPA implementation \
                    annotated @ConditionalOnProperty(name="feature-enable-database", \
                    havingValue="true") before turning this flag on. Set it back to false \
                    to use the YAML file store.""");
        }
        log.info("Storage: YAML file store (feature-enable-database=false)");
    }
}
