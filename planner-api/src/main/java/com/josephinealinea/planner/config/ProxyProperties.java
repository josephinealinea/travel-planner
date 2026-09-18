package com.josephinealinea.planner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The secret the Cloudflare Pages proxy sends as X-Proxy-Secret on every
 * request it forwards. Set only in the cloud; blank means "no proxy in front"
 * and ProxySecretFilter does nothing, so local development is unchanged.
 *
 * A record of its own rather than a component of AppProperties, which is
 * constructed positionally in a score of tests — see the deployment plan.
 */
@ConfigurationProperties(prefix = "app.proxy")
public record ProxyProperties(String secret) {
    public ProxyProperties {
        if (secret == null) secret = "";
        secret = secret.strip();
    }

    public boolean enforced() {
        return !secret.isEmpty();
    }
}
