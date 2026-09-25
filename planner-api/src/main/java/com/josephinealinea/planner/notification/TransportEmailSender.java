package com.josephinealinea.planner.notification;

/**
 * An {@link EmailSender} that actually delivers: log, file or smtp, chosen by
 * app.mail.mode. Services never see this type. They are given the gate in front
 * of it, {@link EventGatedEmailSender}, which is what lets an event be switched
 * off without any of them knowing.
 */
public interface TransportEmailSender extends EmailSender {
}
