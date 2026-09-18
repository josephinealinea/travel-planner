package com.josephinealinea.planner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers ProxyProperties. Kept apart from PlannerApiApplication so the
 * deployment work adds its settings without editing the shared entry points.
 */
@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfig {
}
