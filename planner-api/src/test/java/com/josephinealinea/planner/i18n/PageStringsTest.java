package com.josephinealinea.planner.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The published page's script may only ask for words that exist. */
class PageStringsTest {

    /** Any 'page.…' literal: a lookup, or an entry in a table of keys (weather conditions, themes). */
    private static final Pattern LOOKUP = Pattern.compile("'(page\\.[A-Za-z0-9_.-]+)'");

    private static String read(String resource) throws IOException {
        try (var in = PageStringsTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyKeyPageJsLooksUpExistsInEnglish() throws IOException {
        Properties en = new Properties();
        try (Reader r = new InputStreamReader(
                getClass().getResourceAsStream("/messages_en.properties"), StandardCharsets.UTF_8)) {
            en.load(r);
        }
        Set<String> missing = new TreeSet<>();
        Matcher m = LOOKUP.matcher(read("/publish/page.js"));
        while (m.find()) {
            String key = m.group(1);
            if (!en.containsKey(key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "page.js asks for words messages_en.properties lacks: " + missing);
    }

    @Test
    void pageJsDoesNotAskForAnythingOutsideThePageNamespace() throws IOException {
        Set<String> outside = new TreeSet<>();
        Matcher m = Pattern.compile("\\bt\\(\\s*'([^']+)'").matcher(read("/publish/page.js"));
        while (m.find()) if (!m.group(1).startsWith("page.")) outside.add(m.group(1));
        assertTrue(outside.isEmpty(), "Only page.* keys are shipped to a public page: " + outside);
    }

    /**
     * The point of the exercise: no sentence of the page's own is left in the
     * script. Every English value the page.* keys hold must not appear as a
     * quoted literal in page.js (one that is only a placeholder, or too short
     * to be a sentence, is exempt).
     */
    @Test
    void noEnglishTheKeysCoverIsLeftHardcodedInPageJs() throws IOException {
        Properties en = new Properties();
        try (Reader r = new InputStreamReader(
                getClass().getResourceAsStream("/messages_en.properties"), StandardCharsets.UTF_8)) {
            en.load(r);
        }
        String js = read("/publish/page.js");
        Set<String> left = new TreeSet<>();
        for (String key : en.stringPropertyNames()) {
            if (!key.startsWith("page.")) continue;
            String value = en.getProperty(key);
            if (value.length() < 4 || value.contains("{")) continue;
            if (js.contains("'" + value + "'")) left.add(key + " = " + value);
        }
        assertTrue(left.isEmpty(), "Still hardcoded in page.js:\n" + String.join("\n", left));
    }
}
