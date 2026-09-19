package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailTemplatesTest {

    private final MailTemplates mail = new MailTemplates(props("https://travellingllama.fun/"));

    /** Every message says where it came from, whichever one it is. */
    @Test
    void everyMessageIsSignedWithTheBrand() {
        List<Email> all = List.of(
                mail.invitedNewMember("a@example.com", "LATAM", "Maia", "temp-pass"),
                mail.addedExistingMember("a@example.com", "LATAM", "Maia"),
                mail.removedFromTrip("a@example.com", "LATAM"),
                mail.publishRequested("a@example.com", "LATAM", "Maia"),
                mail.publishApproved("a@example.com", "LATAM", "https://travellingllama.fun/p/latam"),
                mail.publishRejected("a@example.com", "LATAM"),
                mail.tripPublished(List.of("a@example.com"), "LATAM", "https://travellingllama.fun/p/latam"));

        assertThat(all).allSatisfy(email ->
                assertThat(email.body()).endsWith("\n\n— 🦙 Travelling Llama\nhttps://travellingllama.fun\n"));
    }

    /** The subject stays about the trip; the brand lives in the sign-off. */
    @Test
    void subjectsAreUnchanged() {
        assertThat(mail.addedExistingMember("a@example.com", "LATAM", "Maia").subject())
                .isEqualTo("You have been added to LATAM");
    }

    /** The screens call the people on a trip travel buddies; the email agrees. */
    @Test
    void theInvitationCallsThemTravelBuddies() {
        String body = mail.invitedNewMember("a@example.com", "LATAM", "Maia", "temp-pass").body();

        assertThat(body).contains("travel buddies").doesNotContain("member");
    }

    @Test
    void signInLinkStillPointsAtTheSite() {
        assertThat(mail.addedExistingMember("a@example.com", "LATAM", "Maia").body())
                .contains("Open it at https://travellingllama.fun/login.html");
    }

    private static AppProperties props(String origin) {
        return new AppProperties(
                new AppProperties.Storage("/tmp"),
                new AppProperties.Publish("/tmp", "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(List.of(origin)),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));
    }
}
