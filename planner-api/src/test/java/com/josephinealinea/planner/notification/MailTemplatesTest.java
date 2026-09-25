package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.i18n.I18nConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailTemplatesTest {

    private final MailTemplates mail = new MailTemplates(props("https://travellingllama.fun/"), I18nConfig.standalone());

    /** Every message says where it came from, whichever one it is. */
    @Test
    void everyMessageIsSignedWithTheBrand() {
        List<Email> all = List.of(
                mail.invitedNewMember(null, "a@example.com", "LATAM", "Maia", "temp-pass"),
                mail.addedExistingMember(null, "a@example.com", "LATAM", "Maia"),
                mail.removedFromTrip(null, "a@example.com", "LATAM"),
                mail.publishRequested(null, "a@example.com", "LATAM", "Maia"),
                mail.publishApproved(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam"),
                mail.publishRejected(null, "a@example.com", "LATAM"),
                mail.tripPublished(null, List.of("a@example.com"), "LATAM", "https://travellingllama.fun/p/latam"));

        assertThat(all).allSatisfy(email ->
                assertThat(email.body()).endsWith("\n\n— 🦙 Travelling Llama\nhttps://travellingllama.fun\n"));
    }

    /** The subject stays about the trip; the brand lives in the sign-off. */
    @Test
    void subjectsAreUnchanged() {
        assertThat(mail.addedExistingMember(null, "a@example.com", "LATAM", "Maia").subject())
                .isEqualTo("You have been added to LATAM");
    }

    /** The screens call the people on a trip travel buddies; the email agrees. */
    @Test
    void theInvitationCallsThemTravelBuddies() {
        String body = mail.invitedNewMember(null, "a@example.com", "LATAM", "Maia", "temp-pass").body();

        assertThat(body).contains("travel buddies").doesNotContain("member");
    }

    @Test
    void signInLinkStillPointsAtTheSite() {
        assertThat(mail.addedExistingMember(null, "a@example.com", "LATAM", "Maia").body())
                .contains("Open it at https://travellingllama.fun/login.html");
    }

    @Test
    void aRecipientWhoReadsAnotherLanguageGetsThatLanguageSubjectAndBody() {
        Email email = mail.addedExistingMember("xx", "a@example.com", "LATAM", "Maia");

        assertThat(email.subject()).isEqualTo("[xx] You have been added to LATAM");
        assertThat(email.body()).startsWith("[xx] Maia added you to the trip \"LATAM\".");
    }

    @Test
    void aLanguageWeDoNotHaveIsEnglish() {
        assertThat(mail.addedExistingMember("fr", "a@example.com", "LATAM", "Maia").subject())
                .isEqualTo("You have been added to LATAM");
    }

    /** MessageFormat treats ' and { specially in the pattern, never in an argument. */
    @Test
    void namesWithPunctuationArePrintedAsTheyAre() {
        Email email = mail.publishRequested(null, "a@example.com", "50% off {sale}", "Ma'ia \"M\"");

        assertThat(email.subject()).isEqualTo("Ma'ia \"M\" wants to publish 50% off {sale}");
        assertThat(email.body()).contains("Ma'ia \"M\" has asked to publish the trip \"50% off {sale}\".")
                .contains("Open the trip's Publish tab at");
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