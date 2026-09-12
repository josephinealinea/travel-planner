package com.josephinealinea.planner.notification;

/** One outgoing message, already rendered. */
public record Email(String to, String subject, String body) {}
