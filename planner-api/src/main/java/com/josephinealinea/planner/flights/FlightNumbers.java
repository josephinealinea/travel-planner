package com.josephinealinea.planner.flights;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One spelling of a flight number everywhere it is compared or stored: no
 * spaces, upper case. {@code kl 2842}, {@code KL 2842} and {@code KL2842} are
 * the same flight, and a cache keyed on the raw text would treat them as three.
 */
public final class FlightNumbers {

    // Two-character airline designator (IATA letters and digits), 1-4 digits, an optional letter suffix.
    private static final Pattern SHAPE = Pattern.compile("^[A-Z0-9]{2}\\d{1,4}[A-Z]?$");

    private FlightNumbers() {}

    /** Null stays null. */
    public static String normalise(String raw) {
        return raw == null ? null : raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    public static boolean valid(String normalised) {
        return normalised != null && SHAPE.matcher(normalised).matches();
    }
}
