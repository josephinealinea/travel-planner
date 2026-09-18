package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.destinations.PerTripStores;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything an {@link ItineraryRepository} must do on top of the generic
 * per-trip contract, run against YAML and PostgreSQL alike.
 */
public abstract class ItineraryRepositoryContract extends TripScopedRepositoryContract<ItineraryItem> {

    /** Never stored anywhere: the legacy destination-id holder exists only to read old YAML. */
    private static final String[] UNSTORED = {"legacyDestinationIds"};

    protected abstract ItineraryRepository store();

    @Override
    protected TripScopedRepository<ItineraryItem> repository() {
        return store();
    }

    protected static String tripIdOf(String slug) {
        return "trip-" + slug;
    }

    @Override
    protected ItineraryItem entity(String id, String label) {
        ItineraryItem item = new ItineraryItem();
        item.setId(id);
        item.setTripId(tripIdOf(TRIP));
        item.setDescription(label);
        return item;
    }

    @Override
    protected String idOf(ItineraryItem entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(ItineraryItem entity) {
        return entity.getDescription();
    }

    // ---- ordering and finders ----------------------------------------------

    @Test
    void chronologicalWithUndatedLastThenSortOrder() {
        store().save(TRIP, at("undated-0", null, 0));
        store().save(TRIP, at("25-oct-06:00-5", LocalDateTime.of(2026, 10, 25, 6, 0), 5));
        store().save(TRIP, at("24-oct-23:00", LocalDateTime.of(2026, 10, 24, 23, 0), 9));
        store().save(TRIP, at("25-oct-06:00-1", LocalDateTime.of(2026, 10, 25, 6, 0), 1));
        store().save(TRIP, at("undated-minus-1", null, -1));
        store().save(TRIP, at("25-oct-06:00-1-later", LocalDateTime.of(2026, 10, 25, 6, 0), 1));

        assertThat(store().findAllOrdered(TRIP)).extracting(ItineraryItem::getId).containsExactly(
                "24-oct-23:00", "25-oct-06:00-1", "25-oct-06:00-1-later", "25-oct-06:00-5",
                "undated-minus-1", "undated-0");
    }

    @Test
    void findByChecklistItemIsThatItemsEntriesInItineraryOrder() {
        ItineraryItem later = at("later", LocalDateTime.of(2026, 10, 27, 9, 0), 0);
        later.setChecklistItemId("check-1");
        ItineraryItem other = at("other", LocalDateTime.of(2026, 10, 26, 9, 0), 0);
        other.setChecklistItemId("check-2");
        ItineraryItem earlier = at("earlier", LocalDateTime.of(2026, 10, 25, 9, 0), 0);
        earlier.setChecklistItemId("check-1");
        ItineraryItem loose = at("loose", LocalDateTime.of(2026, 10, 24, 9, 0), 0);
        store().saveAll(TRIP, List.of(later, other, earlier, loose));

        assertThat(store().findByChecklistItem(TRIP, "check-1")).extracting(ItineraryItem::getId)
                .containsExactly("earlier", "later");
        assertThat(store().findByChecklistItem(TRIP, "nothing")).isEmpty();
        assertThat(store().findByChecklistItem(OTHER_TRIP, "check-1")).isEmpty();
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void everyFieldComesBackAsItWasSaved() {
        ItineraryItem full = fullyPopulated();
        PerTripStores.assertEveryStoredFieldIsSet(full, UNSTORED);

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .ignoringFields(UNSTORED)
                .isEqualTo(full);
    }

    @Test
    void anEntryWithNothingButItsIdComesBackThatWay() {
        ItineraryItem bare = new ItineraryItem();
        bare.setId("bare");
        bare.setTripId(tripIdOf(TRIP));

        store().save(TRIP, bare);

        ItineraryItem loaded = store().findById(TRIP, "bare").orElseThrow();
        assertThat(loaded).usingRecursiveComparison().ignoringFields(UNSTORED).isEqualTo(bare);
        assertThat(loaded.getAllDay()).as("never said is not false").isNull();
    }

    @Test
    void allDayHasThreeValues() {
        ItineraryItem yes = entity("yes", "yes");
        yes.setAllDay(true);
        ItineraryItem no = entity("no", "no");
        no.setAllDay(false);
        ItineraryItem unsaid = entity("unsaid", "unsaid");
        store().saveAll(TRIP, List.of(yes, no, unsaid));

        assertThat(store().findById(TRIP, "yes").orElseThrow().getAllDay()).isTrue();
        assertThat(store().findById(TRIP, "no").orElseThrow().getAllDay()).isFalse();
        assertThat(store().findById(TRIP, "unsaid").orElseThrow().getAllDay()).isNull();
    }

    @Test
    void aSixAmDepartureIsStillSixAm() {
        ItineraryItem flight = entity("flight", "LA2025 Lima to Cusco");
        flight.setStartAt(LocalDateTime.of(2026, 10, 25, 6, 0));
        flight.setEndAt(LocalDateTime.of(2026, 10, 25, 7, 25));

        store().save(TRIP, flight);

        ItineraryItem loaded = store().findById(TRIP, "flight").orElseThrow();
        assertThat(loaded.getStartAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 6, 0));
        assertThat(loaded.getEndAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 7, 25));
    }

    @Test
    void costsComeBackExactlyScaleIncluded() {
        List<String> costs = List.of("246.22", "0.1", "10.50", "1234.5678", "0", "99999999.99");
        for (int i = 0; i < costs.size(); i++) {
            ItineraryItem item = entity("cost-" + i, "cost " + costs.get(i));
            item.setCost(new BigDecimal(costs.get(i)));
            store().save(TRIP, item);
        }

        for (int i = 0; i < costs.size(); i++) {
            assertThat(store().findById(TRIP, "cost-" + i).orElseThrow().getCost())
                    .isEqualTo(new BigDecimal(costs.get(i)))   // equals, so scale must match too
                    .hasToString(costs.get(i));
        }
    }

    @Test
    void aNullCategoryReadsBackAsOthers() {
        ItineraryItem item = entity("x", "x");
        item.setCategory(null);

        store().save(TRIP, item);

        assertThat(store().findById(TRIP, "x").orElseThrow().getCategory()).isEqualTo(ChecklistCategory.OTHERS);
    }

    private ItineraryItem at(String id, LocalDateTime startAt, int sortOrder) {
        ItineraryItem item = entity(id, id);
        item.setStartAt(startAt);
        item.setSortOrder(sortOrder);
        return item;
    }

    static ItineraryItem fullyPopulated() {
        ItineraryItem item = new ItineraryItem();
        item.setId("itin-night-2");
        item.setTripId(tripIdOf(TRIP));
        item.setChecklistItemId("check-lodging-cusco");
        item.setPlanId("itin-night-1");
        item.setCategory(ChecklistCategory.LODGING);
        item.setDescription("Casa San Blas, night 2");
        item.setStartAt(LocalDateTime.of(2026, 10, 26, 6, 0));
        item.setEndAt(LocalDateTime.of(2026, 10, 27, 11, 30, 15));
        item.setAllDay(true);
        item.setCost(new BigDecimal("246.22"));
        item.setCurrency("PEN");
        item.setBudgetItemId("budget-casa-san-blas");
        item.setCountryCodes(List.of("PE", "BO"));
        item.setSortOrder(4);
        item.setCreatedAt(Instant.parse("2026-09-01T10:15:30.123456Z"));
        item.setCreatedByUserId("user-ana");
        item.setUpdatedAt(Instant.parse("2026-09-02T08:00:00Z"));
        item.setUpdatedByUserId("user-ben");
        return item;
    }
}
