package com.josephinealinea.planner.config;

import com.josephinealinea.planner.shared.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * Double-submit CSRF: the token is issued as a readable cookie and must come
 * back as a header on every state-changing request. Spring Security's own CSRF
 * support is disabled in favour of this, because it is the exact contract the
 * existing frontend fetch wrapper already speaks.
 */
@Component
public class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final AuthCookies cookies;
    private final FilterErrors errors;

    public CsrfFilter(AuthCookies cookies, FilterErrors errors) {
        this.cookies = cookies;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Optional<String> existing = cookies.read(request, AuthCookies.CSRF_COOKIE);
        String token = existing.orElseGet(() -> Ids.token(32));
        if (existing.isEmpty()) {
            cookies.setCsrf(response, token);
        }

        if (!SAFE_METHODS.contains(request.getMethod())) {
            String header = request.getHeader(AuthCookies.CSRF_HEADER);
            if (existing.isEmpty() || header == null || !constantTimeEquals(header, token)) {
                errors.write(request, response, HttpStatus.FORBIDDEN, "csrf_failed", "error.csrf.failed");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // The published pages are plain reads with no cookies involved.
        return request.getRequestURI().startsWith("/p/");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }
}
