package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, AppProperties props) {
        this.mailSender = mailSender;
        this.from = props.mail().from();
    }

    @Override
    public void send(Email email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mailSender.send(message);
        } catch (Exception e) {
            // A mail outage must not roll back the action that triggered it.
            log.error("Could not send email to {}", email.to(), e);
        }
    }
}
