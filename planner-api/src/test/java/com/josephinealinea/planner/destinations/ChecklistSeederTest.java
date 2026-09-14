package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.domain.Destination;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ChecklistSeederTest {

    private final ChecklistSeeder seeder = new ChecklistSeeder();

    private static Destination cusco(LocalDate start, LocalDate end) {
        Destination destination = new Destination();
        destination.setId("dest-1");
        destination.setTripId("trip-1");
        destination.setName("Cusco");
        destination.setCountryCode("PE");
        destination.setStartDate(start);
        destination.setEndDate(end);
        return destination;
    }

    @Test
    void seedsThreeItemsWithTheNightsCountWhenBothDatesAreGiven() {
        List<ChecklistItem> seeded = seeder.seedFor(
                cusco(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)), 0);

        assertThat(seeded)
                .extracting(ChecklistItem::getCategory, ChecklistItem::getDescription)
                .containsExactly(
                        tuple(ChecklistCategory.TRANSPORTATION, "Plan transportation to Cusco"),
                        tuple(ChecklistCategory.LODGING, "Plan 6N accommodation in Cusco"),
                        tuple(ChecklistCategory.ACTIVITIES, "Plan activities in Cusco"));
    }

    @Test
    void seedsNoAccommodationUntilTheDatesCoverANight() {
        // Nothing to book for a night nobody stays, so these seed two items,
        // not three with vague wording.
        assertThat(seeder.seedFor(cusco(null, null), 0))
                .extracting(ChecklistItem::getCategory)
                .containsExactly(ChecklistCategory.TRANSPORTATION, ChecklistCategory.ACTIVITIES);

        // One date alone says nothing about how long anyone stays.
        assertThat(seeder.seedFor(cusco(LocalDate.of(2026, 10, 25), null), 0))
                .extracting(ChecklistItem::getCategory)
                .containsExactly(ChecklistCategory.TRANSPORTATION, ChecklistCategory.ACTIVITIES);

        // A day trip: arrive and leave on the 24th, sleep somewhere else.
        assertThat(seeder.seedFor(cusco(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 24)), 0))
                .extracting(ChecklistItem::getCategory)
                .containsExactly(ChecklistCategory.TRANSPORTATION, ChecklistCategory.ACTIVITIES);
    }

    @Test
    void sortOrderStaysContiguousWhenAccommodationIsSkipped() {
        // The activities item takes the slot accommodation would have had,
        // rather than leaving a hole in the ordering.
        assertThat(seeder.seedFor(cusco(null, null), 4))
                .extracting(ChecklistItem::getSortOrder)
                .containsExactly(4, 5);
    }

    @Test
    void needsAccommodationIsTheNightsTest() {
        assertThat(seeder.needsAccommodation(cusco(null, null))).isFalse();
        assertThat(seeder.needsAccommodation(
                cusco(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 24)))).isFalse();
        assertThat(seeder.needsAccommodation(
                cusco(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25)))).isTrue();
    }

    @Test
    void theAccommodationItemOnItsOwnCarriesTheNightsCount() {
        ChecklistItem item = seeder.accommodationFor(
                cusco(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)), 9);

        assertThat(item.getDescription()).isEqualTo("Plan 6N accommodation in Cusco");
        assertThat(item.getCategory()).isEqualTo(ChecklistCategory.LODGING);
        assertThat(item.getSortOrder()).isEqualTo(9);
        assertThat(item.getSeededFromDestinationId()).isEqualTo("dest-1");
    }

    @Test
    void seededItemsStartAsTodoAndAreLinkedToTheDestinationsCountry() {
        List<ChecklistItem> seeded = seeder.seedFor(
                cusco(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)), 7);

        assertThat(seeded).allSatisfy(item -> {
            assertThat(item.getStatus()).isEqualTo(ChecklistStatus.TODO);
            // The link is the country; the city is kept only as provenance,
            // which is what lets PlanTemplates still suggest Cusco's own dates.
            assertThat(item.getCountryCodes()).containsExactly("PE");
            assertThat(item.getSeededFromDestinationId()).isEqualTo("dest-1");
            assertThat(item.getTripId()).isEqualTo("trip-1");
            assertThat(item.isAutoSeeded()).isTrue();
            assertThat(item.getId()).isNotBlank();
        });
        // Sort order continues from the existing checklist length.
        assertThat(seeded).extracting(ChecklistItem::getSortOrder).containsExactly(7, 8, 9);
    }
}
