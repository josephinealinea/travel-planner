package com.josephinealinea.planner.identity.api;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.geocoding.CountryTable;
import com.josephinealinea.planner.identity.domain.PublishedPageSettings;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.Emails;
import com.josephinealinea.planner.shared.Ids;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties props;

    public UserService(UserRepository users, PasswordEncoder encoder, AppProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
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
                    user.setCurrencies(new ArrayList<>(props.currencies().defaults()));
                    user.setDisplayCurrency(props.currencies().defaultDisplay());
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

    public User updateProfile(String userId, String screenName, String homeCountryCode, String email) {
        User user = require(userId);
        if (email != null && !email.isBlank()) changeEmail(user, email);
        String trimmed = screenName == null ? null : screenName.trim();
        if (trimmed != null && trimmed.length() > 60) {
            throw ApiException.badRequest("Screen name must be 60 characters or fewer.");
        }
        user.setScreenName(trimmed == null || trimmed.isBlank() ? null : trimmed);
        String code = homeCountryCode == null ? null : homeCountryCode.trim().toUpperCase();
        if (code != null && !code.isEmpty() && !CountryTable.isKnown(code)) {
            throw ApiException.badRequest("Choose a country from the list.");
        }
        user.setHomeCountryCode(code == null || code.isEmpty() ? null : code);
        return users.save(user);
    }

    private void changeEmail(User user, String email) {
        String normalised = YamlUserRepository.normalise(email);
        if (!normalised.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw ApiException.badRequest("That does not look like an email address.");
        }
        if (normalised.equals(user.getEmail())) return;
        if (users.findByEmail(normalised).isPresent()) {
            throw ApiException.conflict("email_taken", "That email address is already in use.");
        }
        user.setEmail(normalised);
    }

    /**
     * A stand-in for somebody who has left a trip they were part of the budget
     * of: the same person on paper (name, country, currencies, tier), under
     * left-<its id>-<their email>, with a password nobody knows. The trip keeps
     * it as a member so their expenses still have somebody to belong to.
     */
    public User createLeftCopy(String userId) {
        User original = require(userId);
        User copy = new User();
        copy.setId(Ids.newId());
        copy.setEmail(YamlUserRepository.normalise("left-" + copy.getId() + "-" + original.getEmail()));
        copy.setScreenName(original.getScreenName());
        copy.setHomeCountryCode(original.getHomeCountryCode());
        copy.setTierLevel(original.getTierLevel());
        copy.setCurrencies(new ArrayList<>(original.getCurrencies()));
        copy.setDisplayCurrency(original.getDisplayCurrency());
        copy.setPublishedPage(original.getPublishedPage());
        copy.setPasswordHash(encoder.encode(Ids.defaultPassword()));
        copy.setMustChangePassword(true);
        return users.save(copy);
    }

    /**
     * Takes the person's email address out of the system without deleting the
     * account: the row, its trips and its history stay, and the address becomes
     * deactivated-<id>@example.com. The caller signs them out.
     */
    public User deactivateEmail(String userId) {
        User user = require(userId);
        user.setEmail(Emails.deactivated(user.getId()));
        return users.save(user);
    }

    /**
     * A user's own working currencies — seeded from app.currencies.defaults
     * when their account is created, fully replaceable after that. This is
     * what the "record a cost" forms offer (a plan, an itinerary entry, an
     * expense, the trip's own display currency); it has nothing to do with
     * which currencies a particular trip's budget happens to contain, which
     * is derived from that trip's own data instead.
     */
    public User updateCurrencies(String userId, List<String> currencies) {
        User user = require(userId);
        if (currencies == null || currencies.isEmpty()) {
            throw ApiException.badRequest("Choose at least one currency.");
        }
        if (currencies.size() > 20) {
            throw ApiException.badRequest("That is too many currencies — choose 20 or fewer.");
        }
        List<String> normalised = new ArrayList<>();
        for (String code : currencies) {
            if (code == null || !code.trim().matches("[A-Za-z]{3}")) {
                throw ApiException.badRequest("\"" + code + "\" does not look like a currency code.");
            }
            String upper = code.trim().toUpperCase();
            if (!normalised.contains(upper)) normalised.add(upper);
        }
        user.setCurrencies(normalised);
        return users.save(user);
    }

    /**
     * The single currency this user's budget totals show in, wherever they
     * are — independent of the currencies list above and of any trip's own
     * displayCurrency (that stays the anchor its exchange-rate table is
     * quoted against; see TripService.rebase). A trip with no rate for this
     * currency reports it in currenciesMissingRates rather than guessing.
     */
    /**
     * The account's published-page settings. Null leaves a flag as it is, so a
     * single checkbox can post on its own without the caller having to send
     * the whole group back.
     */
    public User updatePublishedPageSettings(String userId, Boolean itineraryCost,
                                           Boolean destinationDays,
                                           Boolean forecastExpenses,
                                           Boolean displayHomeCountry,
                                           Boolean displayBudget) {
        User user = require(userId);
        PublishedPageSettings settings = user.getPublishedPage();
        if (itineraryCost != null) settings.setItineraryCost(itineraryCost);
        if (destinationDays != null) settings.setDestinationDays(destinationDays);
        if (forecastExpenses != null) settings.setForecastExpenses(forecastExpenses);
        if (displayHomeCountry != null) settings.setDisplayHomeCountry(displayHomeCountry);
        if (displayBudget != null) settings.setDisplayBudget(displayBudget);
        return users.save(user);
    }

    public User updateDisplayCurrency(String userId, String currency) {
        User user = require(userId);
        if (currency == null || !currency.trim().matches("[A-Za-z]{3}")) {
            throw ApiException.badRequest("\"" + currency + "\" does not look like a currency code.");
        }
        user.setDisplayCurrency(currency.trim().toUpperCase());
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
