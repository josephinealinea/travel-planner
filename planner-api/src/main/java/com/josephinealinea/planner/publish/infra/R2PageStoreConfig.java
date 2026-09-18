package com.josephinealinea.planner.publish.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.r2.*} only when pages go to R2, so a filesystem install —
 * local development, every test — never reads or validates those keys.
 * R2PageStore itself carries the same condition.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.publish.store", havingValue = "r2")
@EnableConfigurationProperties(R2Properties.class)
public class R2PageStoreConfig {
}
