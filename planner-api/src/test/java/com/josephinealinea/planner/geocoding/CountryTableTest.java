package com.josephinealinea.planner.geocoding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CountryTableTest {

    @Test
    void namesAKnownCodeCaseInsensitively() {
        assertThat(CountryTable.nameOf("PE")).isEqualTo("Peru");
        assertThat(CountryTable.nameOf("pe")).isEqualTo("Peru");
        assertThat(CountryTable.nameOf(" bo ")).isEqualTo("Bolivia");
    }

    @Test
    void anUnknownOrMissingCodeHasNoName() {
        assertThat(CountryTable.nameOf("ZZ")).isNull();
        assertThat(CountryTable.nameOf(null)).isNull();
    }
}
