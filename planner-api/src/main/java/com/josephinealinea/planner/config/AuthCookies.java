package com.josephinealinea.planner.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Optional;

/**
 * The session token lives in an httpOnly cookie so no script can read it. The
 * CSRF token is a readable companion cookie that the frontend echoes back as a
 * header — the double-submit pattern.
 */
@Component
public class AuthCookies {

    public static final String SESSION = "tp_session";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final boolean secure;

    public AuthCookies(AppProperties props) {
        this.secure = props.security().cookieSecure();
    }

    public void setSession(HttpServletResponse response, String token, long maxAgeSeconds) {
        Cookie cookie = new Cookie(SESSION, token);
        cookie.setHttpOnly(true);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setMaxAge((int) maxAgeSeconds);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public void clearSession(HttpServletResponse response) {
        setSession(response, "", 0);
    }

    /** Readable by JavaScript on purpose — that is the point of double-submit. */
    public void setCsrf(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(CSRF_COOKIE, token);
        cookie.setHttpOnly(false);
        cookie.setSecure(secure);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    public Optional<String> readSession(HttpServletRequest request) {
        return read(request, SESSION);
    }

    public Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
