package com.josephinealinea.planner.trips.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public final class TripDtos {

    private TripDtos() {}

    public record CreateTripRequest(
            @NotBlank(message = "Give the trip a title")
            @Size(max = 120, message = "That title is too long") String title,
            @NotNull(message = "Pick a start date") LocalDate startDate,
            @NotNull(message = "Pick an end date") LocalDate endDate) {}

    public record UpdateTripRequest(
            @Size(max = 120, message = "That title is too long") String title,
            LocalDate startDate,
            LocalDate endDate,
            String displayCurrency,
            Map<String, BigDecimal> exchangeRates) {}

    public record AddMemberRequest(
            @NotBlank(message = "Enter an email address")
            @Email(message = "That does not look like an email address") String email) {}
}
