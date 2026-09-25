package com.josephinealinea.planner.shared;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class Slugs {

    private Slugs() {}

    /** "LATAM Trip 2026" -> "latam-trip-2026". Also used as the YAML filename. */
    public static String of(String text) {
        String slug = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "trip" : slug;
    }

    /** Appends -2, -3 ... until the slug is free. */
    public static String unique(String text, Predicate<String> taken) {
        String base = of(text);
        String candidate = base;
        int suffix = 2;
        while (taken.test(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /**
     * Slugs land in file paths, so anything that could escape the storage root
     * has to be rejected rather than sanitised.
     */
    public static String requireSafe(String slug) {
        if (slug == null || slug.isBlank()
                || !slug.matches("[a-z0-9][a-z0-9-]*")
                || slug.contains("..")
                || Set.of(".", "..").contains(slug)) {
            throw ApiException.badRequest("error.identifier.invalid", slug);
        }
        return slug;
    }
}
