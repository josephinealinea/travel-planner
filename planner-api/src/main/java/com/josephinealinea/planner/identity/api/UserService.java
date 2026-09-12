package com.josephinealinea.planner.identity.api;

import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /** What a newly invited member needs: the account, plus the plaintext to email them. */
    public record Invited(User user, String defaultPassword, boolean created) {}

    /**
     * Find-or-create by email. This is what makes the member list able to show
     * an email address for someone who has never signed in and a screen name for
     * someone who has: every invited member gets an account immediately, with
     * mustChangePassword set and no screen name yet.
     */
    public Invited findOrCreate(String email) {
        String normalised = YamlUserRepository.normalise(email);
        if (normalised == null || !normalised.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("That does not look like an email address.");
        }
        return users.findByEmail(normalised)
                .map(existing -> new Invited(existing, null, false))
                .orElseGet(() -> {
                    String password = Ids.defaultPassword();
                    User user = new User();
                    user.setId(Ids.newId());
                    user.setEmail(normalised);
                    user.setScreenName(null);
                    user.setPasswordHash(encoder.encode(password));
                    user.setMustChangePassword(true);
                    return new Invited(users.save(user), password, true);
                });
    }

    public User require(String id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    public Map<String, User> byId(List<String> ids) {
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** Display name for a member id, falling back to the recorded email. */
    public String displayNameOr(String userId, String fallbackEmail) {
        return users.findById(userId).map(User::displayName).orElse(fallbackEmail);
    }

    public User updateScreenName(String userId, String screenName) {
        User user = require(userId);
        String trimmed = screenName == null ? null : screenName.trim();
        if (trimmed != null && trimmed.length() > 60) {
            throw ApiException.badRequest("Screen name must be 60 characters or fewer.");
        }
        user.setScreenName(trimmed == null || trimmed.isBlank() ? null : trimmed);
        return users.save(user);
    }

    /**
     * Changing the password is also what clears the mustChangePassword gate, and
     * it accepts an optional screen name so the forced first-run screen can do
     * both in a single call.
     */
    public User changePassword(String userId, String currentPassword, String newPassword, String screenName) {
        User user = require(userId);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "current_password_wrong", "That current password is not right.");
        }
        if (newPassword == null || newPassword.length() < 8) {
            throw ApiException.badRequest("Your new password needs at least 8 characters.");
        }
        if (encoder.matches(newPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("Please choose a password you have not used here before.");
        }
        user.setPasswordHash(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        if (screenName != null && !screenName.isBlank()) {
            user.setScreenName(screenName.trim());
        }
        return users.save(user);
    }
}
