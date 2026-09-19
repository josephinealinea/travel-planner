package com.josephinealinea.planner.checklist;

import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.domain.ChecklistItem;
import com.josephinealinea.planner.checklist.domain.ChecklistStatus;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.storage.EveryField;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a {@link ChecklistRepository} must do on top of the generic
 * per-trip contract, run against YAML and PostgreSQL alike.
 */
public abstract class ChecklistRepositoryContract extends TripScopedRepositoryContract<ChecklistItem> {

    /** Never stored anywhere: the legacy destination-id holder exists only to read old YAML. */
    private static final String[] UNSTORED = {"legacyDestinationIds"};

    protected abstract ChecklistRepository store();

    @Override
    protected TripScopedRepository<ChecklistItem> repository() {
        return store();
    }

    protected static String tripIdOf(String slug) {
        return "trip-" + slug;
    }

    @Override
    protected ChecklistItem entity(String id, String label) {
        ChecklistItem item = new ChecklistItem();
        item.setId(id);
        item.setTripId(tripIdOf(TRIP));
        item.setDescription(label);
        return item;
    }

    @Override
    protected String idOf(ChecklistItem entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(ChecklistItem entity) {
        return entity.getDescription();
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    void toDoComesBeforeCompletedThenSortOrder() {
        store().save(TRIP, item("todo-2", ChecklistStatus.TODO, 2));
        store().save(TRIP, item("done-0", ChecklistStatus.COMPLETED, 0));
        store().save(TRIP, item("todo-1", ChecklistStatus.TODO, 1));
        store().save(TRIP, item("done-minus-1", ChecklistStatus.COMPLETED, -1));
        store().save(TRIP, item("todo-1-later", ChecklistStatus.TODO, 1));

        assertThat(store().findAllOrdered(TRIP)).extracting(ChecklistItem::getId)
                .containsExactly("todo-1", "todo-1-later", "todo-2", "done-minus-1", "done-0");
    }

    @Test
    void aFullTieFollowsTheListOrderReplaceAllGave() {
        store().saveAll(TRIP, List.of(item("a", ChecklistStatus.TODO, 0), item("b", ChecklistStatus.TODO, 0)));
        store().replaceAll(TRIP, List.of(item("b", ChecklistStatus.TODO, 0), item("a", ChecklistStatus.TODO, 0)));

        assertThat(store().findAllOrdered(TRIP)).extracting(ChecklistItem::getId).containsExactly("b", "a");
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void everyFieldComesBackAsItWasSaved() {
        ChecklistItem full = fullyPopulated();
        EveryField.assertEverySet(full, UNSTORED);

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .ignoringFields(UNSTORED)
                .isEqualTo(full);
    }

    @Test
    void anItemWithNothingButItsIdComesBackThatWay() {
        ChecklistItem bare = new ChecklistItem();
        bare.setId("bare");
        bare.setTripId(tripIdOf(TRIP));

        store().save(TRIP, bare);

        assertThat(store().findById(TRIP, "bare")).get()
                .usingRecursiveComparison()
                .ignoringFields(UNSTORED)
                .isEqualTo(bare);
    }

    @Test
    void aNullCategoryAndStatusReadBackAsTheirDefaults() {
        ChecklistItem item = entity("x", "Book Machu Picchu tickets");
        item.setCategory(null);
        item.setStatus(null);

        store().save(TRIP, item);

        ChecklistItem loaded = store().findById(TRIP, "x").orElseThrow();
        assertThat(loaded.getCategory()).isEqualTo(ChecklistCategory.OTHERS);
        assertThat(loaded.getStatus()).isEqualTo(ChecklistStatus.TODO);
    }

    @Test
    void countryCodesKeepTheirOrder() {
        ChecklistItem item = entity("x", "Border crossing");
        item.setCountryCodes(List.of("PE", "BO", "CL"));

        store().save(TRIP, item);

        assertThat(store().findById(TRIP, "x").orElseThrow().getCountryCodes()).containsExactly("PE", "BO", "CL");
    }

    /**
     * "Not set" follows the parent and "[]" is explicitly the whole trip, so
     * the store must keep them apart. See trips.api.Travellers.
     */
    @Test
    void notSetAndWholeTripAreDifferentTravellerStates() {
        ChecklistItem unset = entity("unset", "Book Uyuni tour");
        ChecklistItem everyone = entity("everyone", "Visas");
        everyone.setTravellerIds(List.of());
        ChecklistItem named = entity("named", "Cusco hotel");
        named.setTravellerIds(List.of("user-sam", "user-alex"));

        store().save(TRIP, unset);
        store().save(TRIP, everyone);
        store().save(TRIP, named);

        assertThat(store().findById(TRIP, "unset").orElseThrow().getTravellerIds()).isNull();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isNotNull().isEmpty();
        assertThat(store().findById(TRIP, "named").orElseThrow().getTravellerIds())
                .containsExactly("user-sam", "user-alex");
    }

    private ChecklistItem item(String id, ChecklistStatus status, int sortOrder) {
        ChecklistItem item = entity(id, id);
        item.setStatus(status);
        item.setSortOrder(sortOrder);
        return item;
    }

    static ChecklistItem fullyPopulated() {
        ChecklistItem item = new ChecklistItem();
        item.setId("check-lodging-cusco");
        item.setTripId(tripIdOf(TRIP));
        item.setCountryCodes(List.of("PE", "BO"));
        item.setSeededFromDestinationId("dest-cusco");
        item.setCategory(ChecklistCategory.LODGING);
        item.setDescription("Plan 6N accommodation in Cusco");
        item.setNote("Near San Blas, not Plaza de Armas.");
        item.setStatus(ChecklistStatus.COMPLETED);
        item.setAutoSeeded(true);
        item.setSortOrder(7);
        item.setCreatedAt(Instant.parse("2026-09-01T10:15:30.123456Z"));
        item.setCreatedByUserId("user-ana");
        item.setUpdatedAt(Instant.parse("2026-09-03T12:00:00Z"));
        item.setUpdatedByUserId("user-ben");
        item.setCompletedAt(Instant.parse("2026-09-03T12:00:00Z"));
        item.setTravellerIds(List.of("user-ana", "user-ben"));
        return item;
    }
}
