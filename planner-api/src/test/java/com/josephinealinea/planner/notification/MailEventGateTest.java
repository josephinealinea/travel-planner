package com.josephinealinea.planner.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MailEventGateTest {

    private final List<Email> delivered = new ArrayList<>();
    private final TransportEmailSender transport = delivered::add;

    private static Email email(MailEvent event) {
        return new Email(event, "a@example.com", "Subject", "Body\n");
    }

    @Test
    void everyEventIsOnUnlessSaidOtherwise() {
        var gate = new EventGatedEmailSender(transport, MailEventProperties.allOn());

        for (MailEvent event : MailEvent.values()) gate.send(email(event));

        assertThat(delivered).hasSize(MailEvent.values().length);
    }

    @Test
    void aSwitchedOffEventIsNotSentAndTheOthersAre() {
        var gate = new EventGatedEmailSender(transport,
                new MailEventProperties(Map.of(MailEvent.PUBLISH_REQUESTED, false)));

        gate.send(email(MailEvent.PUBLISH_REQUESTED));
        gate.send(email(MailEvent.PUBLISH_APPROVED));

        assertThat(delivered).extracting(Email::event).containsExactly(MailEvent.PUBLISH_APPROVED);
    }

    /** The names an operator types in application.yml or an environment variable. */
    @Test
    void theSettingsNameEachEventInLowerCaseWithHyphens() {
        Map<String, String> yaml = new java.util.LinkedHashMap<>();
        for (MailEvent event : MailEvent.values()) yaml.put("app.mail-events.enabled." + event.key(), "true");
        yaml.put("app.mail-events.enabled.payment-recorded", "false");

        var bound = new Binder(new MapConfigurationPropertySource(yaml))
                .bind("app.mail-events", MailEventProperties.class).get();

        assertThat(bound.isEnabled(MailEvent.PAYMENT_RECORDED)).isFalse();
        assertThat(bound.isEnabled(MailEvent.INVITED_NEW_MEMBER)).isTrue();
    }

    /** application.yml must carry a switch for every event, or one cannot be turned off from outside. */
    @Test
    void applicationYmlListsEveryEvent() throws Exception {
        String yml = new String(getClass().getClassLoader().getResourceAsStream("application.yml").readAllBytes());

        for (MailEvent event : MailEvent.values()) {
            assertThat(yml).contains(event.key() + ": ${MAIL_EVENT_" + event.name() + ":true}");
        }
    }
}
