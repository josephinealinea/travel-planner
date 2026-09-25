package com.josephinealinea.planner.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.NoSuchMessageException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private final Messages messages = I18nConfig.standalone();

    @Test
    void readsEnglish() {
        assertEquals("Plain text", messages.get(Locale.ENGLISH, "test.plain"));
    }

    @Test
    void readsAnotherLanguage() {
        assertEquals("[xx] Plain text", messages.get(Locale.forLanguageTag("xx"), "test.plain"));
    }

    @Test
    void anUnknownLanguageFallsBackToEnglish() {
        assertEquals("Plain text", messages.get(Locale.forLanguageTag("fr"), "test.plain"));
    }

    @Test
    void aKeyMissingFromALanguageFallsBackToEnglish() {
        assertEquals("Only in English", messages.get(Locale.forLanguageTag("xx"), "test.english.only"));
    }

    @Test
    void aKeyMissingEverywhereIsAnErrorNotABlank() {
        assertThrows(NoSuchMessageException.class, () -> messages.get(Locale.ENGLISH, "no.such.key"));
    }

    @Test
    void argumentsAreInsertedLiterally() {
        // A name like O'Brien or a title with braces must not be read as pattern syntax.
        assertEquals("Hello O'Brien {x}, you have 50% trips",
                messages.get(Locale.ENGLISH, "test.args", "O'Brien {x}", "50%"));
    }

    @Test
    void supportedLanguagesAreThoseWithAFile() {
        assertTrue(messages.supported().contains("en"));
        assertTrue(messages.supported().contains("xx"), "the test locale is on the test classpath");
    }

    @Test
    void anArgumentCanBeAMessageItselfAndIsTranslatedWithTheRest() {
        Msg part = new Msg("test.part");
        assertEquals("part and part", messages.get(Locale.ENGLISH, "test.and", part, part));
        assertEquals("[xx] part [xx]and [xx] part",
                messages.get(Locale.forLanguageTag("xx"), "test.and", part, part));
    }
}
