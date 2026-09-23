package com.josephinealinea.planner.publish.api;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.publish.require-owner-approval} (env {@code REQUIRE_OWNER_APPROVAL}).
 *
 * True, the default: only the owner publishes, and another member's Publish
 * button raises a request the owner approves. False: any member publishes and
 * re-publishes directly, and requests are not raised. Unpublishing is the
 * owner's either way.
 *
 * A record of its own rather than a component of AppProperties, which is
 * constructed positionally in a score of tests.
 */
@ConfigurationProperties(prefix = "app.publish")
public record PublishApprovalProperties(Boolean requireOwnerApproval) {

    public PublishApprovalProperties {
        if (requireOwnerApproval == null) requireOwnerApproval = true;
    }

    public static PublishApprovalProperties required() {
        return new PublishApprovalProperties(true);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PublishApprovalProperties.class)
    public static class Registration {
    }
}
