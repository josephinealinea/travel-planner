package com.josephinealinea.planner.shared;

import java.security.SecureRandom;
import java.util.UUID;

public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] READABLE = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private Ids() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * A default password a person can retype from an email without squinting —
     * no look-alike characters (0/O, 1/l/I).
     */
    public static String defaultPassword() {
        return "Trip-" + readable(4) + "-" + readable(4);
    }

    public static String token(int length) {
        return readable(length);
    }

    private static String readable(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(READABLE[RANDOM.nextInt(READABLE.length)]);
        }
        return out.toString();
    }
}
