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
        return AuthDtos.MeResponse.from(users.updateScreenName(currentUser.userId(), request.screenName()));
    }

    /**
     * Also the exit from the forced-change gate, and it takes an optional screen
     * name so the first-run screen can do both in a single request. A fresh
     * cookie goes out so the session clock restarts from the new password.
     */
    @PostMapping("/password")
    AuthDtos.MeResponse changePassword(@Valid @RequestBody AuthDtos.ChangePasswordRequest request,
                                       HttpServletResponse response) {
        User updated = users.changePassword(
                currentUser.userId(), request.currentPassword(), request.newPassword(), request.screenName());
        cookies.setSession(response, jwt.issue(updated.getId()), jwt.ttl().toSeconds());
        return AuthDtos.MeResponse.from(updated);
    }

    /**
     * Replaces the caller's own currency list wholesale — the same shape as
     * updateProfile, not a partial add/remove. This is what the "record a
     * cost" forms offer; it has no bearing on a trip's own "Show totals in"
     * list, which is derived from that trip's data instead.
     */
    @PatchMapping("/currencies")
    AuthDtos.MeResponse updateCurrencies(@RequestBody AuthDtos.UpdateCurrenciesRequest request) {
        return AuthDtos.MeResponse.from(users.updateCurrencies(currentUser.userId(), request.currencies()));
    }

    /**
     * What this account's published pages show. Grouped under one endpoint
     * because more of these are coming, and every one of them is a checkbox.
     *
     * Takes effect on the next publish, not retroactively: a published page is
     * a rendered file, so what it shows was decided when it was written.
     */
    @PatchMapping("/published-page")
    AuthDtos.MeResponse updatePublishedPage(
            @RequestBody AuthDtos.UpdatePublishedPageRequest request) {
        return AuthDtos.MeResponse.from(
                users.updatePublishedPageSettings(currentUser.userId(),
                        request.itineraryCost(), request.destinationDays(),
                        request.forecastExpenses()));
    }

    /**
     * The single currency this account's budget totals are shown in, on every
     * trip. It has no bearing on a trip's own displayCurrency, which stays the
     * anchor its exchange-rate table is quoted against.
     */
    @PatchMapping("/display-currency")
    AuthDtos.MeResponse updateDisplayCurrency(@RequestBody AuthDtos.UpdateDisplayCurrencyRequest request) {
        return AuthDtos.MeResponse.from(users.updateDisplayCurrency(currentUser.userId(), request.displayCurrency()));
    }
}
