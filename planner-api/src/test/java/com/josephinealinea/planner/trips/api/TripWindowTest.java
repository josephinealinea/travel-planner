package com.josephinealinea.planner.trips.api;

import com.josephinealinea.planner.shared.ApiException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one rule behind "a date recorded against this trip has to be inside it",
 * shared by destinations, the itinerary, the checklist's Plan form and the
 * budget. The interesting cases are all at the edges: both ends are inclusive,
 * an absent date is not an error, and a trip with no dates of its own cannot
 * constrain anything.
 */
class TripWindowTest {

    private static final TripWindow WINDOW =
            new TripWindow(LocalDate.parse("2026-12-20"), LocalDate.parse("2026-12-27"));

    @Test
    void bothEndsOfTheTripAreInside() {
        // A flight on the first day and a checkout on the last are the normal
        // case, not the exception.
        assertThatCode(() -> WINDOW.require(LocalDate.parse("2026-12-20"), "start date"))
                .doesNotThrowAnyException();
        assertThatCode(() -> WINDOW.require(LocalDate.parse("2026-12-27"), "end date"))
                .doesNotThrowAnyException();
    }

    @Test
    void theDayBeforeAndTheDayAfterAreRejected() {
        assertThatThrownBy(() -> WINDOW.require(LocalDate.parse("2026-12-19"), "start date"))
                .isInstanceOf(ApiException.class)
                .hasMessage("The start date must be within the trip dates, 2026-12-20 to 2026-12-27.");
        assertThatThrownBy(() -> WINDOW.require(LocalDate.parse("2026-12-28"), "end date"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theRejectionIsADeliberateClientError() {
        // Not a bare IllegalArgumentException — see the project's Traps note.
        ApiException thrown = (ApiException) org.assertj.core.api.Assertions
                .catchThrowable(() -> WINDOW.require(LocalDate.parse("2027-01-05"), "expense date"));

        assertThat(thrown.status().value()).isEqualTo(400);
        assertThat(thrown.code()).isEqualTo("bad_request");
        assertThat(thrown.getMessage()).contains("expense date");
    }

    @Test
    void anAbsentDateIsNotAnError() {
        // Every one of these fields is optional; only a value that is there
        // has to be inside the trip.
        assertThatCode(() -> WINDOW.require((LocalDate) null, "start date")).doesNotThrowAnyException();
        assertThatCode(() -> WINDOW.require((LocalDateTime) null, "start date")).doesNotThrowAnyException();
    }

    @Test
    void aTripWithNoDatesConstrainsNothing() {
        // Trips created through the API always have both, but a hand-written
        // YAML file need not — and inventing a window there would reject
        // perfectly good data.
        TripWindow open = new TripWindow(null, null);

        assertThatCode(() -> open.require(LocalDate.parse("1999-01-01"), "date")).doesNotThrowAnyException();
    }

    @Test
    void onlyTheDayPartOfAnItineraryTimeIsChecked() {
        // A plan at 23:30 on the last day is inside the trip; the same plan a
        // minute past midnight is not.
        assertThatCode(() -> WINDOW.require(LocalDateTime.parse("2026-12-27T23:30"), "end date"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> WINDOW.require(LocalDateTime.parse("2026-12-28T00:01"), "end date"))
                .isInstanceOf(ApiException.class);
    }
}
