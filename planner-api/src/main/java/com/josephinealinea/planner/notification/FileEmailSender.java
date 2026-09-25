package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

/**
 * The default. Writes a readable .eml into data/outbox, which is how you get a
 * newly invited member's default password in local development without running
 * a mail server.
 */
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "file", matchIfMissing = true)
public class FileEmailSender implements TransportEmailSender {

    private static final Logger log = LoggerFactory.getLogger(FileEmailSender.class);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final YamlStore store;
    private final YamlPaths paths;
    private final String from;

    public FileEmailSender(YamlStore store, YamlPaths paths, AppProperties props) {
        this.store = store;
        this.paths = paths;
        this.from = props.mail().from();
    }

    @Override
    public void send(Email email) {
        String safeTo = email.to().replaceAll("[^a-zA-Z0-9._@-]", "_");
        String name = STAMP.format(Instant.now()) + "-" + safeTo + ".eml";
        String contents = """
                From: %s
                To: %s
                Subject: %s
                Date: %s
                Content-Type: text/plain; charset=utf-8

                %s
                """.formatted(from, email.to(), email.subject(), Instant.now(), email.body());
        try {
            store.writeText(paths.outbox().resolve(name), contents);
            log.info("Wrote email to {} at {}", email.to(), paths.outbox().resolve(name));
        } catch (RuntimeException e) {
            log.error("Could not write email for {}", email.to(), e);
        }
    }
}
