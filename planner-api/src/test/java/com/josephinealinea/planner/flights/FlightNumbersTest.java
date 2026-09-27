package com.josephinealinea.planner.flights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightNumbersTest {

    /** Review focus 1. */
    @Test
    void oneFlightHasOneSpelling() {
        assertThat(FlightNumbers.normalise("kl 2842")).isEqualTo("KL2842");
        assertThat(FlightNumbers.normalise(" KL2842 ")).isEqualTo("KL2842");
        assertThat(FlightNumbers.normalise("bt857")).isEqualTo("BT857");
        assertThat(FlightNumbers.normalise(null)).isNull();
    }

    @Test
    void shapeIsAirlineCodeDigitsAndAnOptionalLetter() {
        assertThat(FlightNumbers.valid("KL2842")).isTrue();
        assertThat(FlightNumbers.valid("W61968")).isTrue();
        assertThat(FlightNumbers.valid("BA117")).isTrue();
        assertThat(FlightNumbers.valid("AB1234C")).isTrue();
        assertThat(FlightNumbers.valid("HELLO")).isFalse();
        assertThat(FlightNumbers.valid("K")).isFalse();
        assertThat(FlightNumbers.valid("KL")).isFalse();
        assertThat(FlightNumbers.valid("KL12345")).isFalse();
        assertThat(FlightNumbers.valid("")).isFalse();
        assertThat(FlightNumbers.valid(null)).isFalse();
    }
}
