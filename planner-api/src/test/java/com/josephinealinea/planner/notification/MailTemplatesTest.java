package com.josephinealinea.planner.notification;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.i18n.I18nConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MailTemplatesTest {

    private final MailTemplates mail = new MailTemplates(props("https://travellingllama.fun/"), I18nConfig.standalone());

    /** Nothing is appended to a template: what is in the file is what is sent. */
    @Test
    void noMessageCarriesASignOff() {
        List<Email> all = List.of(
                mail.invitedNewMember(null, "a@example.com", "LATAM", "Maia", "temp-pass"),
                mail.addedExistingMember(null, "a@example.com", "LATAM", "Maia"),
                mail.removedFromTrip(null, "a@example.com", "LATAM"),
                mail.publishRequested(null, "a@example.com", "LATAM", "Maia"),
                mail.publishApproved(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", null),
                mail.publishApproved(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", "https://travellingllama.fun/p/latam/m/maia"),
                mail.publishRejected(null, "a@example.com", "LATAM"),
                mail.tripPublished(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", null),
                mail.tripPublished(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", "https://travellingllama.fun/p/latam/m/maia"),
                mail.paymentRecorded(null, "a@example.com", "LATAM", "Maia", "Sam", "Ray", "24.91", "PEN", "2026-10-25"));

        assertThat(all).allSatisfy(email -> {
            assertThat(email.body()).doesNotContain("Travelling Llama").doesNotContain("🦙").endsWith("\n");
            assertThat(email.body()).doesNotEndWith("\n\n");
        });
    }

    /** Every message knows which event it is, so the gate can switch it off. */
    @Test
    void everyMessageNamesItsEvent() {
        assertThat(mail.invitedNewMember(null, "a@example.com", "T", "M", "p").event()).isEqualTo(MailEvent.INVITED_NEW_MEMBER);
        assertThat(mail.addedExistingMember(null, "a@example.com", "T", "M").event()).isEqualTo(MailEvent.ADDED_EXISTING_MEMBER);
        assertThat(mail.removedFromTrip(null, "a@example.com", "T").event()).isEqualTo(MailEvent.REMOVED_FROM_TRIP);
        assertThat(mail.publishRequested(null, "a@example.com", "T", "M").event()).isEqualTo(MailEvent.PUBLISH_REQUESTED);
        assertThat(mail.publishApproved(null, "a@example.com", "T", "u", null).event()).isEqualTo(MailEvent.PUBLISH_APPROVED);
        assertThat(mail.publishApproved(null, "a@example.com", "T", "u", "v").event()).isEqualTo(MailEvent.PUBLISH_APPROVED);
        assertThat(mail.publishRejected(null, "a@example.com", "T").event()).isEqualTo(MailEvent.PUBLISH_REJECTED);
        assertThat(mail.tripPublished(null, "a@example.com", "T", "u", null).event()).isEqualTo(MailEvent.TRIP_PUBLISHED);
        assertThat(mail.tripPublished(null, "a@example.com", "T", "u", "v").event()).isEqualTo(MailEvent.TRIP_PUBLISHED);
        assertThat(mail.paymentRecorded(null, "a@example.com", "T", "M", "P", "R", "1.00", "EUR", "2026-10-25").event())
                .isEqualTo(MailEvent.PAYMENT_RECORDED);
    }

    @Test
    void thePublishedEmailOffersEachMemberTheirOwnPage() {
        assertThat(mail.tripPublished(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam",
                "https://travellingllama.fun/p/latam/m/maia").body())
                .contains("The public page is at https://travellingllama.fun/p/latam")
                .contains("https://travellingllama.fun/p/latam/m/maia");
        assertThat(mail.tripPublished(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", null).body())
                .doesNotContain("/m/");
    }

    @Test
    void aRecordedPaymentSaysWhoPaidWhomAndHowMuch() {
        Email email = mail.paymentRecorded(null, "a@example.com", "LATAM", "Maia", "Sam", "Ray", "24.91", "PEN", "2026-10-25");

        assertThat(email.subject()).isEqualTo("A payment was recorded in LATAM");
        assertThat(email.body()).contains("Maia recorded that Sam paid Ray 24.91 PEN on 2026-10-25.")
                .contains("https://travellingllama.fun/login.html");
    }

    /** The subject stays about the trip. */
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

    /** A value that looks like a placeholder is printed, not filled. */
    @Test
    void aTitleThatLooksLikeAPlaceholderIsPrintedAsItIs() {
        Email email = mail.invitedNewMember(null, "a@example.com", "{{password}}", "Maia", "secret-1");

        assertThat(email.subject()).isEqualTo("You have been added to {{password}}");
        assertThat(email.body()).contains("added you to the trip \"{{password}}\"").contains("Password: secret-1");
    }

    /** The subject is a header, so a line break in a title must not start another. */
    @Test
    void aTitleWithALineBreakStaysOnOneSubjectLine() {
        assertThat(mail.removedFromTrip(null, "a@example.com", "LATAM\r\nBcc: x@example.com").subject())
                .doesNotContain("\n").doesNotContain("\r");
    }

    /** The new-account email carries what the person needs to sign in. */
    @Test
    void theInvitationCarriesTheEmailAndPasswordToSignInWith() {
        String body = mail.invitedNewMember(null, "a@example.com", "LATAM", "Maia", "temp-pass").body();

        assertThat(body).contains("Sign in at https://travellingllama.fun/login.html")
                .contains("Email:    a@example.com").contains("Password: temp-pass");
    }

    /** An approved request's requester is told about their own page as well as the trip's. */
    @Test
    void anApprovalNamesThePersonalPageWhenThereIsOne() {
        String body = mail.publishApproved(null, "a@example.com", "LATAM",
                "https://travellingllama.fun/p/latam", "https://travellingllama.fun/p/latam/m/maia").body();

        assertThat(body).contains("The public page is at https://travellingllama.fun/p/latam")
                .contains("https://travellingllama.fun/p/latam/m/maia");
    }

    @Test
    void anApprovalWithoutAPersonalPageSaysNothingAboutOne() {
        assertThat(mail.publishApproved(null, "a@example.com", "LATAM", "https://travellingllama.fun/p/latam", null).body())
                .doesNotContain("/m/").doesNotContain("Your own page");
    }

    /** The test language has only some files, so the rest are English, not missing. */
    @Test
    void aLanguageWithoutThatTemplateFallsBackToEnglishForIt() {
        assertThat(mail.removedFromTrip("xx", "a@example.com", "LATAM").subject())
                .isEqualTo("You have been removed from LATAM");
    }

    /** Punctuation in a name is printed as it is. */
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