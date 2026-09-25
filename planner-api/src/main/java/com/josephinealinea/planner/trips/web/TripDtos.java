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
            @NotBlank(message = "{validation.trip.titleRequired}")
            @Size(max = 120, message = "{validation.title.tooLong}") String title,
            @NotNull(message = "{validation.startDate.required}") LocalDate startDate,
            @NotNull(message = "{validation.endDate.required}") LocalDate endDate) {}

    public record UpdateTripRequest(
            @Size(max = 120, message = "{validation.title.tooLong}") String title,
            LocalDate startDate,
            LocalDate endDate,
            String displayCurrency) {}

    public record AddMemberRequest(
            @NotBlank(message = "{validation.email.enter}")
            @Email(message = "{validation.email.invalid}") String email) {}
}
