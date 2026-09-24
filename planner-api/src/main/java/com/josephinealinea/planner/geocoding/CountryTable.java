package com.josephinealinea.planner.geocoding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/**
 * The code-to-name table the published page inlines, read here only to check
 * that a code is a real one. Nothing stores or sends a name: the planner's own copy is
 * planner-web/js/countries.js, and {@code npm run check} keeps the two equal.
 */
public final class CountryTable {

    private static final Map<String, String> NAMES = load();

    private CountryTable() {}

    private static Map<String, String> load() {
        try (var in = new ClassPathResource("publish/countries.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Missing publish/countries.json", e);
        }
    }

    public static boolean isKnown(String code) {
        return code != null && NAMES.containsKey(code.trim().toUpperCase(Locale.ROOT));
    }
}
