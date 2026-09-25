package com.josephinealinea.planner.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log")
public class LoggingEmailSender implements TransportEmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(Email email) {
        log.info("""
                
                ---------- email ----------
                To:      {}
                Subject: {}
                
                {}
                ---------------------------""", email.to(), email.subject(), email.body());
    }
}
