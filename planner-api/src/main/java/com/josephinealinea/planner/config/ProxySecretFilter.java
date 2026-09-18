package com.josephinealinea.planner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * In the cloud the API sits behind the Cloudflare Pages proxy, but Cloud Run
 * also gives it a public run.app URL. Without this filter that URL would be a
 * second front door around Cloudflare: every request must carry the proxy's
 * shared secret as X-Proxy-Secret or it is refused.
 *
 * The one exception is /actuator/health, which Cloud Run's own probes call
 * directly and which says nothing about anybody's data. With no secret
 * configured (local development) the filter does nothing at all.
 *
 * Registered first in SecurityConfig, ahead of CORS and CSRF, so a direct
 * caller learns nothing — not even whether it would have needed a cookie.
 */
@Component
public class ProxySecretFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Proxy-Secret";

    private final byte[] secret;

    public ProxySecretFilter(ProxyProperties props) {
        this.secret = props.enforced() ? props.secret().getBytes(StandardCharsets.UTF_8) : null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        boolean matches = header != null
                && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), secret);

        if (!matches) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("""
                    {"status":403,"code":"proxy_required",\
                    "detail":"This API is only reachable through the planner's own site."}""");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (secret == null) return true;
        String uri = request.getRequestURI();
        return uri.equals("/actuator/health") || uri.startsWith("/actuator/health/");
    }
}
