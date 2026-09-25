package com.josephinealinea.planner.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guardrail behind "every message lives in one file". It reads the source
 * rather than running it, so a message nobody has triggered yet is still
 * checked.
 */
class MessageKeysTest {

    private static final Pattern KEY = Pattern.compile("[a-z0-9]+(\\.[a-zA-Z0-9]+)+");

    /** ApiException.badRequest("…"), and conflict("code", "…") where the key is the second literal. */
    private static final Pattern THROWN = Pattern.compile(
            "ApiException\\s*\\.\\s*(badRequest|notFound|forbidden|unauthorized|conflict)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"(?:\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?");

    /** new ApiException(HttpStatus.X, "code", "…"): the key is the third argument. */
    private static final Pattern CONSTRUCTED = Pattern.compile(
            "new\\s+ApiException\\(\\s*[A-Za-z_.]+\\s*,\\s*\"(?:[^\"\\\\]|\\\\.)*\"\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern DECLARED = Pattern.compile("message\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private record Use(Path file, String text) {}

    private static List<Path> sources() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of("src/main/java"))) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static Properties bundle(String resource) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(
                MessageKeysTest.class.getResourceAsStream(resource), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static List<Use> keysUsedInCode() throws IOException {
        List<Use> uses = new ArrayList<>();
        for (Path file : sources()) {
            String text = Files.readString(file);
            Matcher t = THROWN.matcher(text);
            while (t.find()) {
                boolean conflict = t.group(1).equals("conflict");
                String literal = conflict ? t.group(3) : t.group(2);
                if (literal != null) uses.add(new Use(file, literal));
            }
            Matcher c = CONSTRUCTED.matcher(text);
            while (c.find()) uses.add(new Use(file, c.group(1)));
            Matcher d = DECLARED.matcher(text);
            while (d.find()) uses.add(new Use(file, d.group(1)));
        }
        return uses;
    }

    private static String bare(String literal) {
        return literal.startsWith("{") && literal.endsWith("}")
                ? literal.substring(1, literal.length() - 1) : literal;
    }

    @Test
    void everyMessageInCodeIsAKeyNotProse() throws IOException {
        List<String> prose = new ArrayList<>();
        for (Use use : keysUsedInCode()) {
            if (!KEY.matcher(bare(use.text())).matches()) prose.add(use.file() + "  \"" + use.text() + "\"");
        }
        assertTrue(prose.isEmpty(), prose.size() + " message(s) written in code instead of a key:\n"
                + String.join("\n", prose));
    }

    @Test
    void everyKeyUsedInCodeExistsInEnglish() throws IOException {
        Properties en = bundle("/messages_en.properties");
        Set<String> missing = new TreeSet<>();
        for (Use use : keysUsedInCode()) {
            String key = bare(use.text());
            if (KEY.matcher(key).matches() && !en.containsKey(key)) missing.add(key);
        }
        assertTrue(missing.isEmpty(), "Keys used in code but missing from messages_en.properties: " + missing);
    }

    /** Every language file on the classpath: the shipped ones, and the test language. */
    private static java.util.Map<String, Properties> allLanguages() throws IOException {
        java.util.Map<String, Properties> all = new java.util.TreeMap<>();
        for (String code : I18nConfig.standalone().supported()) {
            all.put(code, bundle("/messages_" + code + ".properties"));
        }
        return all;
    }

    @Test
    void aTranslationHasNoKeyEnglishLacks() throws IOException {
        Properties en = bundle("/messages_en.properties");
        Set<String> extra = new TreeSet<>();
        for (var language : allLanguages().entrySet()) {
            for (String key : language.getValue().stringPropertyNames()) {
                // A language names itself (language.name.<its own code>), so English has no such key.
                if (!en.containsKey(key) && !key.startsWith("language.name.")) extra.add(language.getKey() + ": " + key);
            }
        }
        assertTrue(extra.isEmpty(), "Keys in a translation that English does not have (typo?): " + extra);
    }

    @Test
    void anApostropheInAPatternWithArgumentsIsDoubledInEveryLanguage() throws IOException {
        List<String> bad = new ArrayList<>();
        for (var language : allLanguages().entrySet()) {
            Properties messages = language.getValue();
            for (String key : messages.stringPropertyNames()) {
                if (key.startsWith("page.")) continue; // raw patterns, never formatted
                String pattern = messages.getProperty(key);
                // MessageFormat swallows a lone ' and everything up to the next one.
                if (pattern.matches("(?s).*\\{\\d+}.*") && pattern.replace("''", "").contains("'")) bad.add(language.getKey() + ": " + key);
            }
        }
        assertTrue(bad.isEmpty(), "Write '' for an apostrophe in patterns that take arguments: " + bad);
    }
}
