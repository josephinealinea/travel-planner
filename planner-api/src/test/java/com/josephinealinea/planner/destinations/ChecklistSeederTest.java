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
    void omitsTheNightsCountWhenItIsNotCalculable() {
        assertThat(seeder.seedFor(cusco(LocalDate.of(2026, 10, 25), null), 0))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan accommodation in Cusco");

        assertThat(seeder.seedFor(cusco(null, null), 0))
                .extracting(ChecklistItem::getDescription)
                .contains("Plan accommodation in Cusco");
    }

    @Test
    void seededItemsStartAsTodoAndAreLinkedToTheDestination() {
        List<ChecklistItem> seeded = seeder.seedFor(
                cusco(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)), 7);

        assertThat(seeded).allSatisfy(item -> {
            assertThat(item.getStatus()).isEqualTo(ChecklistStatus.TODO);
            assertThat(item.getDestinationId()).isEqualTo("dest-1");
            assertThat(item.getTripId()).isEqualTo("trip-1");
            assertThat(item.isAutoSeeded()).isTrue();
            assertThat(item.getId()).isNotBlank();
        });
        // Sort order continues from the existing checklist length.
        assertThat(seeded).extracting(ChecklistItem::getSortOrder).containsExactly(7, 8, 9);
    }
}
