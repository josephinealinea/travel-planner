package com.josephinealinea.planner.notification;

/**
 * Implementations are chosen by app.mail.mode: log, file or smtp. Sending must
 * never fail the request that triggered it — adding a member has to succeed
 * even when mail is misconfigured.
 */
public interface EmailSender {
    void send(Email email);
}
