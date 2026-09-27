package com.josephinealinea.planner.news;

import java.net.URI;

/**
 * A provider-supplied URL is untrusted: the carousel binds an article's
 * {@code url} straight into an {@code <a href>} and its {@code imageUrl} into
 * a CSS {@code background-image}, with no further check on the frontend. Only
 * {@code http}/{@code https} survives into a {@link com.josephinealinea.planner.news.domain.NewsArticle} —
 * a {@code javascript:} link would otherwise run script on the app's own
 * origin the moment a member clicked a card.
 */
final class SafeUrl {

    private SafeUrl() {}

    /** The url unchanged if it is http/https, else null — never a partial or "cleaned" value. */
    static String httpOnly(String url) {
        if (url == null) return null;
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? url : null;
        } catch (Exception e) {
            return null;
        }
    }
}
