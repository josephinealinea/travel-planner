package com.josephinealinea.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the country link reads and writes, for all three records that carry one.
 *
 * These three used to link to destinations, in two successive shapes — a single
 * `destinationId:` and then a `destinationIds:` list — and `YamlStore` disables
 * FAIL_ON_UNKNOWN_PROPERTIES so a key nothing maps is dropped in silence. A
 * destination id cannot be folded into a country code the way the single id was
 * folded into the list, because only the destinations file knows which country
 * a given id is in. So both old keys are read into a holder the migration can
 * see, rather than being quietly discarded on the first read.
 *
 * What must be true: an old record keeps its ids somewhere until it is
 * migrated, a record with no link reads as unlinked rather than blowing up, and
 * a save writes `countryCodes` and nothing else.
 */
class CountryLinkStorageTest {

    private static final TypeReference<List<ChecklistItem>> CHECKLIST = new TypeReference<>() {};
    private static final TypeReference<List<ItineraryItem>> ITINERARY = new TypeReference<>() {};
    private static final TypeReference<List<BudgetItem>> BUDGET = new TypeReference<>() {};

    private final YamlStore store = new YamlStore();

    @Test
    void countryCodesRoundTrip(@TempDir Path dir) throws Exception {
        ChecklistItem item = new ChecklistItem();
        item.setId("item-1");
        item.setTripId("trip-1");
        item.setCountryCodes(List.of("PE", "BO"));

        Path file = dir.resolve("checklist.yml");
        store.write(file, List.of(item));

        String yaml = Files.readString(file);
        assertThat(yaml).contains("countryCodes");
        assertThat(store.readList(file, CHECKLIST).get(0).getCountryCodes())
                .containsExactly("PE", "BO");
    }

    @Test
    void savingNeverWritesBackEitherOldKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("checklist.yml");
        Files.writeString(file, """
                - id: item-1
                  tripId: trip-1
                  destinationId: dest-1
                  destinationIds:
                  - dest-2
                  category: LODGING
                  description: Plan accommodation in Cusco
                  status: TODO
                  sortOrder: 0
                """);

        List<ChecklistItem> read = store.readList(file, CHECKLIST);
        store.write(file, read);

        String yaml = Files.readString(file);
        assertThat(yaml).doesNotContain("destinationId");
        assertThat(yaml).doesNotContain("destinationIds");
    }

    @Test
    void aChecklistItemsOldKeysAreKeptForTheMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("checklist.yml");
        Files.writeString(file, """
                - id: item-1
                  tripId: trip-1
                  destinationId: dest-1
                  category: LODGING
                  description: Plan accommodation in Cusco
                  status: TODO
                  sortOrder: 0
                - id: item-2
                  tripId: trip-1
                  destinationIds:
                  - dest-2
                  - dest-3
                  category: OTHERS
                  description: Buy a padlock
                  status: TODO
                  sortOrder: 1
                """);

        List<ChecklistItem> items = store.readList(file, CHECKLIST);

        // Nothing resolved yet — a country code is not knowable from here.
        assertThat(items.get(0).getCountryCodes()).isEmpty();
        assertThat(items.get(0).legacyDestinationIds()).containsExactly("dest-1");
        assertThat(items.get(1).legacyDestinationIds()).containsExactly("dest-2", "dest-3");
    }

    @Test
    void anItineraryEntrysOldKeyIsKeptForTheMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("itinerary.yml");
        Files.writeString(file, """
                - id: plan-1
                  tripId: trip-1
                  category: LODGING
                  description: Hotel in Cusco
                  startAt: 2026-10-25T15:00:00
                  destinationIds:
                  - dest-1
                  sortOrder: 0
                """);

        ItineraryItem plan = store.readList(file, ITINERARY).get(0);

        assertThat(plan.getCountryCodes()).isEmpty();
        assertThat(plan.legacyDestinationIds()).containsExactly("dest-1");
    }

    @Test
    void aBudgetRowsOldKeyIsKeptForTheMigration(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("budget.yml");
        Files.writeString(file, """
                - id: budget-1
                  tripId: trip-1
                  category: LODGING
                  description: Hotel in Cusco
                  amount: 240.00
                  currency: USD
                  destinationIds:
                  - dest-1
                  - dest-2
                """);

        BudgetItem row = store.readList(file, BUDGET).get(0);

        assertThat(row.getCountryCodes()).isEmpty();
        assertThat(row.legacyDestinationIds()).containsExactly("dest-1", "dest-2");
    }

    @Test
    void aRecordWithNoLinkAtAllReadsAsUnlinked(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("budget.yml");
        Files.writeString(file, """
                - id: budget-1
                  tripId: trip-1
                  category: OTHERS
                  description: Yellow fever vaccine
                  amount: 45.00
                  currency: USD
                """);

        BudgetItem row = store.readList(file, BUDGET).get(0);

        assertThat(row.getCountryCodes()).isNotNull().isEmpty();
        assertThat(row.legacyDestinationIds()).isNotNull().isEmpty();
    }
}
