package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.i18n.Messages;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every message the app sends. The words live in template files, one per
 * message and language: {@code email/<language>/<name>.txt}. A template's first
 * line is {@code Subject: …}, a blank line follows, and the rest is the body;
 * {@code {{name}}} marks where a value goes. This class only says which values
 * go where, and which {@link MailEvent} each message is.
 *
 * These files are read by nothing but this class, and are separate from
 * {@code messages_<language>.properties}: an email is prose worth reading as
 * prose, not a run of {@code \n\} continuations.
 *
 * Each method takes the recipient's saved language first (null for none). With
 * none, the email is written in the language of the request that caused it — for
 * a brand-new invitee that is the person who invited them, the only language we
 * know anything about. A language with no folder, or a folder missing one file,
 * gets English for it.
 */
@Component
public class MailTemplates {

    static final String DEFAULT_LANGUAGE = "en";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final Messages messages;
    private final String siteUrl;
    private final String signInUrl;
    private final Map<String, String> templates = new ConcurrentHashMap<>();

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
     * Every message is built here from its template. The event travels with
     * the email so the gate in front of the sender can drop it if an operator
     * has switched that event off.
     */
    private Email email(MailEvent event, String language, String to, String name, Map<String, String> values) {
        String lang = localeFor(language).getLanguage();
        String template = template(lang, name);

        int split = template.indexOf("\n\n");
        String first = split < 0 ? template : template.substring(0, split);
        if (split < 0 || !first.startsWith("Subject: ")) {
            throw new IllegalStateException("email template " + name + " must start with 'Subject: …' and a blank line");
        }
        // A subject is a header: a title with a line break in it must not add another.
        String subject = fill(first.substring("Subject: ".length()), values, name).replaceAll("[\\r\\n]+", " ");
        String body = fill(template.substring(split + 2).strip(), values, name);
        return new Email(event, to, subject, body + "\n");
    }

    /** One pass over the template, so a value that looks like {{x}} is printed, never filled. */
    private static String fill(String text, Map<String, String> values, String name) {
        return PLACEHOLDER.matcher(text).replaceAll(match -> {
            String value = values.get(match.group(1));
            if (value == null) {
                throw new IllegalStateException("email template " + name + " names {{" + match.group(1) + "}}, which is not supplied");
            }
            return Matcher.quoteReplacement(value);
        });
    }

    private String template(String language, String name) {
        return templates.computeIfAbsent(language + "/" + name, key -> {
            String text = read(language, name);
            return text != null ? text : Objects.requireNonNull(read(DEFAULT_LANGUAGE, name),
                    () -> "no English email template email/en/" + name + ".txt");
        });
    }

    private static String read(String language, String name) {
        try (InputStream in = MailTemplates.class.getClassLoader()
                .getResourceAsStream("email/" + language + "/" + name + ".txt")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** New account: carries the default password they will be asked to change. */
    public Email invitedNewMember(String language, String to, String tripTitle, String invitedBy,
                                  String defaultPassword) {
        return email(MailEvent.INVITED_NEW_MEMBER, language, to, "invitedNewMember", Map.of("invitedBy", invitedBy, "tripTitle", tripTitle,
                "signInUrl", signInUrl, "email", to, "password", defaultPassword));
    }

    /** Existing account: no credentials, just the news. */
    public Email addedExistingMember(String language, String to, String tripTitle, String invitedBy) {
        return email(MailEvent.ADDED_EXISTING_MEMBER, language, to, "addedExistingMember", Map.of("invitedBy", invitedBy, "tripTitle", tripTitle, "signInUrl", signInUrl));
    }

    public Email removedFromTrip(String language, String to, String tripTitle) {
        return email(MailEvent.REMOVED_FROM_TRIP, language, to, "removedFromTrip", Map.of("tripTitle", tripTitle));
    }

    public Email publishRequested(String language, String to, String tripTitle, String requestedBy) {
        return email(MailEvent.PUBLISH_REQUESTED, language, to, "publishRequested", Map.of("requestedBy", requestedBy, "tripTitle", tripTitle, "signInUrl", signInUrl));
    }

    /**
     * The requester is a member of the trip, so they have a page of their own
     * beside the trip's; {@code personalUrl} is null when there is none to
     * offer (they have since left the trip), and the email then leaves it out.
     */
    public Email publishApproved(String language, String to, String tripTitle, String publicUrl, String personalUrl) {
        if (personalUrl == null) {
            return email(MailEvent.PUBLISH_APPROVED, language, to, "publishApproved", Map.of("tripTitle", tripTitle, "publicUrl", publicUrl));
        }
        return email(MailEvent.PUBLISH_APPROVED, language, to, "publishApprovedWithPersonalPage",
                Map.of("tripTitle", tripTitle, "publicUrl", publicUrl, "personalUrl", personalUrl));
    }

    public Email publishRejected(String language, String to, String tripTitle) {
        return email(MailEvent.PUBLISH_REJECTED, language, to, "publishRejected", Map.of("tripTitle", tripTitle));
    }

    /**
     * Told to each member separately, so each can be given their own page: the
     * trip's page is the same for everybody, theirs is not. {@code personalUrl}
     * is null when there is none to offer, and the email then leaves it out.
     */
    public Email tripPublished(String language, String to, String tripTitle, String publicUrl, String personalUrl) {
        if (personalUrl == null) {
            return email(MailEvent.TRIP_PUBLISHED, language, to, "tripPublished",
                    Map.of("tripTitle", tripTitle, "publicUrl", publicUrl));
        }
        return email(MailEvent.TRIP_PUBLISHED, language, to, "tripPublishedWithPersonalPage",
                Map.of("tripTitle", tripTitle, "publicUrl", publicUrl, "personalUrl", personalUrl));
    }

    /** Told to the other person in a payment, so they know it was recorded and by whom. */
    public Email paymentRecorded(String language, String to, String tripTitle, String recordedBy,
                                 String payer, String receiver, String amount, String currency, String date) {
        return email(MailEvent.PAYMENT_RECORDED, language, to, "paymentRecorded", Map.of(
                "tripTitle", tripTitle, "recordedBy", recordedBy, "payer", payer, "receiver", receiver,
                "amount", amount, "currency", currency, "date", date, "signInUrl", signInUrl));
    }
}
