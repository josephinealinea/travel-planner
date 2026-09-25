package com.josephinealinea.planner.notification;

import java.util.Locale;

/**
 * Every kind of email the app can send, and the unit an operator switches on or
 * off: {@code app.mail-events.enabled.<key>} in application.yml, where the key
 * is the name in lower-case with hyphens (see {@link #key()}).
 *
 * Adding one means an entry here, a template per language under
 * {@code email/<language>/}, a method on {@link MailTemplates}, and a line in
 * application.yml and {@code deploy.sh}.
 */
public enum MailEvent {
    INVITED_NEW_MEMBER,
    ADDED_EXISTING_MEMBER,
    REMOVED_FROM_TRIP,
    PUBLISH_REQUESTED,
    PUBLISH_APPROVED,
    PUBLISH_REJECTED,
    TRIP_PUBLISHED,
    PAYMENT_RECORDED;

    /** The name a setting uses: {@code INVITED_NEW_MEMBER} is {@code invited-new-member}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
