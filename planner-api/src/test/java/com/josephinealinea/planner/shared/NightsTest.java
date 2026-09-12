package com.josephinealinea.planner.shared;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class NightsTest {

    @Test
    void countsNightsBetweenTwoDates() {
        // The worked example from the spec: Cusco, 25-Oct to 31-Oct.
        assertThat(Nights.between(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)))
                .isEqualTo(6L);
    }

    @Test
    void oneNightStay() {
        assertThat(Nights.between(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26)))
                .isEqualTo(1L);
    }

    @Test
    void sameDayIsNotAStay() {
        assertThat(Nights.between(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25)))
                .isNull();
    }

    @Test
    void notCalculableWithOnlyOneDate() {
        assertThat(Nights.between(LocalDate.of(2026, 10, 25), null)).isNull();
        assertThat(Nights.between(null, LocalDate.of(2026, 10, 31))).isNull();
        assertThat(Nights.between(null, null)).isNull();
    }

    @Test
    void reversedDatesDoNotProduceNegativeNights() {
        assertThat(Nights.between(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 25)))
                .isNull();
    }
}
