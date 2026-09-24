package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.geocoding.CountryTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HomeCountryCodeTest {

    @Test
    void codesAreCheckedAgainstTheTable() {
        assertThat(CountryTable.isKnown("pe")).isTrue();
        assertThat(CountryTable.isKnown("ZZ")).isFalse();
        assertThat(CountryTable.isKnown(null)).isFalse();
    }
}
