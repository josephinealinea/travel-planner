package com.josephinealinea.planner.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * What every service is handed as its {@link EmailSender}: drops an email whose
 * event is switched off in {@link MailEventProperties}, and passes the rest to
 * whichever transport app.mail.mode chose. One gate for every event, so a
 * template is never built and then half-sent.
 */
@Component
@Primary
public class EventGatedEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EventGatedEmailSender.class);

    private final TransportEmailSender transport;
    private final MailEventProperties events;

    public EventGatedEmailSender(TransportEmailSender transport, MailEventProperties events) {
        this.transport = transport;
        this.events = events;
    }

    @Override
    public void send(Email email) {
        if (email.event() != null && !events.isEnabled(email.event())) {
            log.info("Not sending {} email to {}: switched off (app.mail-events.enabled.{})",
                    email.event(), email.to(), email.event().key());
            return;
        }
        transport.send(email);
    }
}
