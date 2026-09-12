package com.josephinealinea.planner.identity.web;

import com.josephinealinea.planner.config.AuthCookies;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.config.JwtService;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final UserService users;
    private final CurrentUserContext currentUser;
    private final JwtService jwt;
    private final AuthCookies cookies;

    public AccountController(UserService users,
                             CurrentUserContext currentUser,
                             JwtService jwt,
                             AuthCookies cookies) {
        this.users = users;
        this.currentUser = currentUser;
        this.jwt = jwt;
        this.cookies = cookies;
    }

    @PatchMapping("/profile")
    AuthDtos.MeResponse updateProfile(@RequestBody AuthDtos.ProfileRequest request) {
        return toMe(users.updateScreenName(currentUser.userId(), request.screenName()));
    }

    /**
     * Also the exit from the forced-change gate, and it takes an optional screen
     * name so the first-run screen can set both in one request. A fresh cookie
     * goes out so the session clock restarts from the new password.
     */
    @PostMapping("/password")
    AuthDtos.MeResponse changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                       HttpServletResponse response) {
        User updated = users.changePassword(
                currentUser.userId(), request.currentPassword(), request.newPassword(), request.screenName());
        cookies.setSession(response, jwt.issue(updated.getId()), jwt.ttl().toSeconds());
        return toMe(updated);
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
