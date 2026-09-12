package com.josephinealinea.planner.identity.web;

import com.josephinealinea.planner.config.AuthCookies;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.config.JwtService;
import com.josephinealinea.planner.identity.api.AuthService;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final UserService userService;
    private final JwtService jwt;
    private final AuthCookies cookies;
    private final CurrentUserContext currentUser;

    public AuthController(AuthService auth,
                          UserService userService,
                          JwtService jwt,
                          AuthCookies cookies,
                          CurrentUserContext currentUser) {
        this.auth = auth;
        this.userService = userService;
        this.jwt = jwt;
        this.cookies = cookies;
        this.currentUser = currentUser;
    }

    /** Primes the CSRF cookie. The CsrfFilter does the work; this just needs to be reachable. */
    @GetMapping("/csrf")
    ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    AuthDtos.MeResponse login(@Valid @RequestBody AuthDtos.LoginRequest request,
                              HttpServletResponse response) {
        User user = auth.authenticate(request.email(), request.password());
        cookies.setSession(response, jwt.issue(user.getId()), jwt.ttl().toSeconds());
        return toMe(user);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletResponse response) {
        cookies.clearSession(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Deliberately permitAll and 401 when signed out — the frontend calls this
     * on every page load to decide whether to redirect.
     */
    @GetMapping("/me")
    AuthDtos.MeResponse me() {
        return toMe(userService.require(currentUser.userId()));
    }

    private AuthDtos.MeResponse toMe(User user) {
        return new AuthDtos.MeResponse(
                user.getId(),
                user.getEmail(),
                user.getScreenName(),
                user.displayName(),
                user.isMustChangePassword());
    }
}
