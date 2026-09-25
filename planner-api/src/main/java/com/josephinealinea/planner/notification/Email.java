package com.josephinealinea.planner.notification;

/** One outgoing message, already rendered, and the event it belongs to. */
public record Email(MailEvent event, String to, String subject, String body) {}
