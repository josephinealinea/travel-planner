package com.josephinealinea.planner.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** "xx" is the test language, on the test classpath only; English is the only one that ships. */
class RequestLocaleTest {

    private final RequestLocale locale = new RequestLocale(I18nConfig.standalone(), null);

    private static Locale l(String tag) { return Locale.forLanguageTag(tag); }

    @Test
    void aSignedInMembersChoiceWins() {
        assertEquals(l("xx"), locale.pick("xx", "en-GB"));
    }

    @Test
    void withNoChoiceTheBrowsersFirstSupportedLanguageIsUsed() {
        assertEquals(l("xx"), locale.pick(null, "fr-CA,xx;q=0.8,en;q=0.5"));
    }

    @Test
    void anUnsupportedBrowserLanguageIsEnglishNotAnError() {
        assertEquals(Locale.ENGLISH, locale.pick(null, "fr-CA,de;q=0.7"));
    }

    @Test
    void noHeaderAtAllIsEnglishNotTheServersOwnLocale() {
        assertEquals(Locale.ENGLISH, locale.pick(null, null));
    }

    @Test
    void aGarbageHeaderIsEnglish() {
        assertEquals(Locale.ENGLISH, locale.pick(null, ";;;q=,,,"));
    }

    @Test
    void aStoredLanguageWeNoLongerOfferFallsBackToTheBrowser() {
        assertEquals(l("xx"), locale.pick("retired", "xx"));
        assertEquals(Locale.ENGLISH, locale.pick("retired", null));
    }

    @Test
    void aRegionalVariantOfASupportedLanguageIsThatLanguage() {
        assertEquals(l("xx"), locale.pick(null, "xx-US"));
    }
}
