package com.josephinealinea.planner.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Email(message = "That does not look like an email address") String email,
            @NotBlank(message = "Password is required") String password) {}

    public record MeResponse(
            String id,
            String email,
            String screenName,
            String displayName,
            boolean mustChangePassword) {}

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password") String currentPassword,
            @NotBlank(message = "Enter a new password")
            @Size(min = 8, message = "Your new password needs at least 8 characters") String newPassword,
            String screenName) {}

    public record ProfileRequest(String screenName) {}
}
