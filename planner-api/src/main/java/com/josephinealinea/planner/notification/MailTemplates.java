package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Every message the app sends, in one readable place. */
@Component
public class MailTemplates {

    private final String siteUrl;
    private final String signInUrl;

    public MailTemplates(AppProperties props) {
        this.siteUrl = props.cors().siteUrl();
        this.signInUrl = siteUrl + "/login.html";
    }

    /**
     * Every message goes out through here, so every one ends with the same
     * sign-off: somebody reading an email about a trip should be able to tell
     * where it came from. The subject stays about the trip.
     */
    private Email email(String to, String subject, String body) {
        String text = body.endsWith("\n") ? body : body + "\n";
        return new Email(to, subject, text + "\n— 🦙 Travelling Llama\n" + siteUrl + "\n");
    }

    /** New account: carries the default password they will be asked to change. */
    public Email invitedNewMember(String to, String tripTitle, String invitedBy, String defaultPassword) {
        return email(to,
                "You have been added to " + tripTitle,
                """
                %s added you to the trip "%s".

                Sign in at %s

                  Email:    %s
                  Password: %s

                You will be asked to choose your own password and a screen name the
                first time you sign in. Your screen name is what your
                travel buddies will see.
                """.formatted(invitedBy, tripTitle, signInUrl, to, defaultPassword));
    }

    /** Existing account: no credentials, just the news. */
    public Email addedExistingMember(String to, String tripTitle, String invitedBy) {
        return email(to,
                "You have been added to " + tripTitle,
                """
                %s added you to the trip "%s".

                Open it at %s
                """.formatted(invitedBy, tripTitle, signInUrl));
    }

    public Email removedFromTrip(String to, String tripTitle) {
        return email(to,
                "You have been removed from " + tripTitle,
                "You no longer have access to the trip \"%s\".".formatted(tripTitle));
    }

    public Email publishRequested(String to, String tripTitle, String requestedBy) {
        return email(to,
                "%s wants to publish %s".formatted(requestedBy, tripTitle),
                """
                %s has asked to publish the trip "%s".

                Open the trip's Publish tab at %s to approve or reject it.
                """.formatted(requestedBy, tripTitle, signInUrl));
    }

    public Email publishApproved(String to, String tripTitle, String publicUrl) {
        return email(to,
                "%s is now published".formatted(tripTitle),
                """
                Your request to publish "%s" was approved.

                The public page is at %s
                """.formatted(tripTitle, publicUrl));
    }

    public Email publishRejected(String to, String tripTitle) {
        return email(to,
                "Publish request for %s was declined".formatted(tripTitle),
                "The owner declined the request to publish \"%s\" for now.".formatted(tripTitle));
    }

    public Email tripPublished(List<String> to, String tripTitle, String publicUrl) {
        return email(String.join(", ", to),
                "%s is now published".formatted(tripTitle),
                """
                "%s" has been published.

                The public page is at %s
                """.formatted(tripTitle, publicUrl));
    }
}
