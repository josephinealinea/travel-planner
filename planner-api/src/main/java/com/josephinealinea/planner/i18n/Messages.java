package com.josephinealinea.planner.i18n;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;
import java.util.Set;

/**
 * The one door to every message the API says. Code passes a key; the words
 * live in {@code messages_<language>.properties}. English is the default and the
 * fallback, so a language missing a key shows English rather than nothing, and
 * a key missing from English is an error (a test catches it before a user does).
 */
public class Messages {

    public static final Locale DEFAULT = Locale.ENGLISH;

    private final MessageSource source;
    private final Set<String> supported;

    Messages(MessageSource source, Set<String> supported) {
        this.source = source;
        this.supported = supported;
    }

    /** In the language of the request being served. */
    public String get(String key, Object... args) {
        return get(LocaleContextHolder.getLocale(), key, args);
    }

    public String get(Locale locale, String key, Object... args) {
        args = resolved(locale, args);
        String message = source.getMessage(key, args, null, locale);
        // The files are messages_<language>, with no base file, so English is
        // asked for by name when the requested language has no such key.
        return message != null ? message : source.getMessage(key, args, DEFAULT);
    }

    private Object[] resolved(Locale locale, Object[] args) {
        if (args == null || args.length == 0) return args;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof Msg m ? get(locale, m.key(), m.args()) : args[i];
        }
        return out;
    }

    /**
     * Every {@code prefix}-keyed message in one language, as the raw patterns
     * a static page can use: no MessageFormat is applied (the page fills
     * {@code {name}} placeholders itself), and a key the language lacks is
     * English, so a page never has a hole in it.
     */
    public java.util.Map<String, String> withPrefix(Locale locale, String prefix) {
        java.util.Map<String, String> table = new java.util.TreeMap<>();
        for (String key : java.util.ResourceBundle.getBundle("messages", DEFAULT).keySet()) {
            if (!key.startsWith(prefix)) continue;
            String message = source.getMessage(key, null, null, locale);
            table.put(key, message != null ? message : source.getMessage(key, null, DEFAULT));
        }
        return table;
    }

    /** The supported language a stored or requested code names, else English. */
    public Locale localeOrDefault(String code) {
        return code != null && supported.contains(code.toLowerCase(Locale.ROOT))
                ? Locale.forLanguageTag(code.toLowerCase(Locale.ROOT)) : DEFAULT;
    }

    /** Language codes that have a message file. */
    public Set<String> supported() {
        return supported;
    }
}
