package com.josephinealinea.planner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * While mustChangePassword is set, an invited member can reach only the three
 * endpoints they need to get out of that state. Everything else answers 409
 * password_change_required, which the frontend turns into a redirect to the
 * change-password screen.
 */
@Component
public class PasswordChangeGate extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of(
            "/api/v1/auth/me",
            "/api/v1/auth/login",
            "/api/v1/auth/logout",
            "/api/v1/auth/csrf",
            "/api/v1/account/password");

    private final ObjectProvider<CurrentUserContext> currentUser;
    private final FilterErrors errors;

    public PasswordChangeGate(ObjectProvider<CurrentUserContext> currentUser, FilterErrors errors) {
        this.currentUser = currentUser;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        CurrentUserContext context = currentUser.getObject();
        boolean blocked = context.isAuthenticated()
                && context.get().mustChangePassword()
                && !ALLOWED.contains(request.getRequestURI());

        if (blocked) {
            errors.write(request, response, HttpStatus.CONFLICT,
                    "password_change_required", "error.password.changeRequired");
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/p/");
    }
}
