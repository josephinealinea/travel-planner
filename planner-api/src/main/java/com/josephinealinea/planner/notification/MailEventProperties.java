package com.josephinealinea.planner.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Which emails go out: {@code app.mail-events.enabled.<event>}, one switch per
 * {@link MailEvent}. Anything not mentioned is on, so a new event never
 * silently starts off, and a deployment that sets nothing behaves as it always
 * has. An event name that does not exist fails the start rather than being
 * ignored, so a typo cannot leave an email running that somebody thought they
 * had switched off.
 *
 * Its own record, not part of AppProperties, which many tests construct
 * positionally. The prefix is {@code app.mail-events} rather than
 * {@code app.mail.events} so it does not share a node with that record.
 */
@ConfigurationProperties("app.mail-events")
public record MailEventProperties(Map<MailEvent, Boolean> enabled) {

    public MailEventProperties {
        enabled = enabled == null ? Map.of() : Map.copyOf(enabled);
    }

    public boolean isEnabled(MailEvent event) {
        return enabled.getOrDefault(event, true);
    }

    /** Everything on: what a hand-built service in a test means. */
    public static MailEventProperties allOn() {
        return new MailEventProperties(Map.of());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MailEventProperties.class)
    public static class Registration {
    }
}
