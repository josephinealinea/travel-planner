package com.josephinealinea.planner.identity.web;

import com.josephinealinea.planner.identity.domain.TierLevel;
import com.josephinealinea.planner.identity.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "{validation.email.required}") @Email(message = "{validation.email.invalid}") String email,
            @NotBlank(message = "{validation.password.required}") String password) {}

    public record MeResponse(
            String id,
            String email,
            String screenName,
            String homeCountryCode,
            /** Null until they choose one. */
            String languageCode,
            TierLevel tierLevel,
            String displayName,
            boolean mustChangePassword,
            List<String> currencies,
            String displayCurrency,
            /** Published-page settings, grouped so adding the next is one field. */
            PublishedPage publishedPage) {

        public static MeResponse from(User user) {
            return new MeResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getScreenName(),
                    user.getHomeCountryCode(),
                    user.getLanguageCode(),
                    user.getTierLevel(),
                    user.displayName(),
                    user.isMustChangePassword(),
                    user.getCurrencies(),
                    user.getDisplayCurrency(),
                    new PublishedPage(user.getPublishedPage().isItineraryCost(),
                            user.getPublishedPage().isDestinationDays(),
                            user.getPublishedPage().isForecastExpenses(),
                            user.getPublishedPage().isDisplayHomeCountry(),
                            user.getPublishedPage().isDisplayBudget()));
        }
    }

    /**
     * What a published page shows. All flags, all defaulting to off: a
     * published page is public, so anything extra it reveals should be asked
     * for rather than assumed.
     *
     * Flat on the wire though it is one document in storage — the page posts a
     * checkbox at a time, and nesting it would only make the request deeper
     * without making it say more.
     */
    public record PublishedPage(boolean itineraryCost,
                                boolean destinationDays,
                                boolean forecastExpenses,
                                boolean displayHomeCountry,
                                boolean displayBudget) {}

    /** Absent fields are left as they are, so one checkbox can post alone. */
    public record UpdatePublishedPageRequest(Boolean itineraryCost,
                                            Boolean destinationDays,
                                            Boolean forecastExpenses,
                                            Boolean displayHomeCountry,
                                            Boolean displayBudget) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "{validation.currentPassword.required}") String currentPassword,
            @NotBlank(message = "{validation.newPassword.required}")
            @Size(min = 8, message = "{validation.newPassword.tooShort}") String newPassword,
            String screenName) {}

    public record LanguageRequest(String languageCode) {}

    public record ProfileRequest(String screenName, String homeCountryCode, String email) {}

    public record UpdateCurrenciesRequest(List<String> currencies) {}

    public record UpdateDisplayCurrencyRequest(String displayCurrency) {}
}
