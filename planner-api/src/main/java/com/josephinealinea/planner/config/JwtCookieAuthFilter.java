package com.josephinealinea.planner.config;

import com.josephinealinea.planner.identity.api.AuthService;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Turns the session cookie into an authenticated request. The user is re-read
 * from storage on every request rather than trusted from the token, so a
 * screen-name change or a freshly cleared mustChangePassword flag takes effect
 * immediately instead of at the next sign-in.
 */
@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AuthCookies cookies;
    private final UserRepository users;
    private final AuthService authService;
    private final ObjectProvider<CurrentUserContext> currentUser;

    public JwtCookieAuthFilter(JwtService jwt,
                               AuthCookies cookies,
                               UserRepository users,
                               AuthService authService,
                               ObjectProvider<CurrentUserContext> currentUser) {
        this.jwt = jwt;
        this.cookies = cookies;
        this.users = users;
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<User> user = cookies.readSession(request)
                .flatMap(jwt::verify)
                .flatMap(users::findById);

        user.ifPresent(found -> {
            currentUser.getObject().set(authService.toCurrentUser(found));
            var authentication = new UsernamePasswordAuthenticationToken(
                    found.getId(), null, AuthorityUtils.createAuthorityList("ROLE_USER"));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        });

        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
