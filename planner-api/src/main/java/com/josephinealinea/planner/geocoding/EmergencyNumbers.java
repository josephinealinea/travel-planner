package com.josephinealinea.planner.geocoding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/**
 * The general emergency number for a country. countries.dev does not carry
 * one, so this is a small static table in {@code publish/emergency-numbers.json}.
 * A country not listed answers null and the page simply leaves the field out.
 */
public final class EmergencyNumbers {

    private static final Map<String, String> NUMBERS = load();

    private EmergencyNumbers() {}

    private static Map<String, String> load() {
        try (var in = new ClassPathResource("publish/emergency-numbers.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<Map<String, String>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Missing publish/emergency-numbers.json", e);
        }
    }

    public static String of(String code) {
        return code == null ? null : NUMBERS.get(code.trim().toUpperCase(Locale.ROOT));
    }
}
