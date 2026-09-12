package com.josephinealinea.planner.identity.api;

import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.shared.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    public User authenticate(String email, String password) {
        // One message for both branches so this cannot be used to test which
        // email addresses have accounts.
        ApiException rejected = ApiException.unauthorized("That email or password is not right.");
        User user = users.findByEmail(email).orElseThrow(() -> rejected);
        if (!encoder.matches(password, user.getPasswordHash())) throw rejected;
        return user;
    }

    public CurrentUser toCurrentUser(User user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.displayName(), user.isMustChangePassword());
    }
}
