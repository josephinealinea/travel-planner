package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.i18n.Messages;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Every message the app sends. The words live in {@code messages_<language>.properties}
 * under {@code email.<name>.subject} and {@code .body}; this class only says
 * which arguments go where.
 *
 * Each method takes the recipient's saved language first (null for none). With
 * none, the email is written in the language of the request that caused it — for
 * a brand-new invitee that is the person who invited them, the only language we
 * know anything about.
 */
@Component
public class MailTemplates {

    private final Messages messages;
    private final String siteUrl;
    private final String signInUrl;

    public MailTemplates(AppProperties props, Messages messages) {
        this.messages = messages;
        this.siteUrl = props.cors().siteUrl();
        this.signInUrl = siteUrl + "/login.html";
    }

    private Locale localeFor(String recipientLanguage) {
        if (recipientLanguage != null && messages.supported().contains(recipientLanguage.toLowerCase(Locale.ROOT))) {
            return Locale.forLanguageTag(recipientLanguage);
        }
        return LocaleContextHolder.getLocale();
    }

    /**
     * Every message goes out through here, so every one ends with the same
     * sign-off: somebody reading an email about a trip should be able to tell
     * where it came from. The subject stays about the trip.
     */
    private Email email(String language, String to, String name, Object... args) {
        Locale locale = localeFor(language);
        String subject = messages.get(locale, "email." + name + ".subject", args);
        String body = messages.get(locale, "email." + name + ".body", args);
        String text = body.endsWith("\n") ? body : body + "\n";
        return new Email(to, subject, text + "\n" + messages.get(locale, "email.signoff") + "\n" + siteUrl + "\n");
    }

    /** New account: carries the default password they will be asked to change. */
    public Email invitedNewMember(String language, String to, String tripTitle, String invitedBy,
                                  String defaultPassword) {
        return email(language, to, "invitedNewMember", invitedBy, tripTitle, signInUrl, to, defaultPassword);
    }

    /** Existing account: no credentials, just the news. */
    public Email addedExistingMember(String language, String to, String tripTitle, String invitedBy) {
        return email(language, to, "addedExistingMember", invitedBy, tripTitle, signInUrl);
    }

    public Email removedFromTrip(String language, String to, String tripTitle) {
        return email(language, to, "removedFromTrip", tripTitle);
    }

    public Email publishRequested(String language, String to, String tripTitle, String requestedBy) {
        return email(language, to, "publishRequested", requestedBy, tripTitle, signInUrl);
    }

    public Email publishApproved(String language, String to, String tripTitle, String publicUrl) {
        return email(language, to, "publishApproved", tripTitle, publicUrl);
    }

    public Email publishRejected(String language, String to, String tripTitle) {
        return email(language, to, "publishRejected", tripTitle);
    }

    /** One message to several people, so it is written in the language of whoever published. */
    public Email tripPublished(String language, List<String> to, String tripTitle, String publicUrl) {
        return email(language, String.join(", ", to), "tripPublished", tripTitle, publicUrl);
    }
}
