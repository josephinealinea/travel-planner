package com.josephinealinea.planner.i18n;

import com.josephinealinea.planner.config.CurrentUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Which language a request is answered in: the signed-in member's own choice,
 * else the first language their browser asks for that has a messages file,
 * else English. Never an error — an unsupported language is just English.
 *
 * Also Spring MVC's {@link LocaleResolver} (the bean must be called
 * {@code localeResolver}), so controllers, the exception handler and anything
 * on the request thread see the same answer.
 */
@Component("localeResolver")
public class RequestLocale implements LocaleResolver {

    private final Messages messages;
    private final ObjectProvider<CurrentUserContext> currentUser;

    public RequestLocale(Messages messages, ObjectProvider<CurrentUserContext> currentUser) {
        this.messages = messages;
        this.currentUser = currentUser;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return of(request);
    }

    /** Filters call this too: they run before MVC and have no locale of their own. */
    public Locale of(HttpServletRequest request) {
        return pick(signedInLanguage(), request.getHeader("Accept-Language"));
    }

    private String signedInLanguage() {
        if (currentUser == null) return null;
        CurrentUserContext context = currentUser.getIfAvailable();
        return context != null && context.isAuthenticated() ? context.get().languageCode() : null;
    }

    /**
     * The rule itself, apart from the request it usually comes from.
     *
     * @param stored the member's saved language, or null
     * @param acceptLanguage the raw Accept-Language header, or null
     */
    public Locale pick(String stored, String acceptLanguage) {
        if (stored != null && messages.supported().contains(stored.toLowerCase(Locale.ROOT))) {
            return Locale.forLanguageTag(stored);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                for (Locale.LanguageRange range : Locale.LanguageRange.parse(acceptLanguage)) {
                    String language = range.getRange().split("-")[0].toLowerCase(Locale.ROOT);
                    if (messages.supported().contains(language)) return Locale.forLanguageTag(language);
                }
            } catch (IllegalArgumentException malformed) {
                // A header nobody could parse asks for nothing in particular.
            }
        }
        return Messages.DEFAULT;
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("The language is stored on the account, not the request");
    }
}
