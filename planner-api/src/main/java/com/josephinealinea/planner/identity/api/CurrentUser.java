package com.josephinealinea.planner.identity.api;

/** The authenticated principal, resolved from the session cookie per request. */
public record CurrentUser(String id, String email, String displayName, boolean mustChangePassword,
                          String languageCode) {}
