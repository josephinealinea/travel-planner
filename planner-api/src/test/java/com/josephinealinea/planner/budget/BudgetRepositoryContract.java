package com.josephinealinea.planner.budget;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.domain.BudgetStatus;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.storage.EveryField;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a {@link BudgetRepository} must do on top of the generic per-trip
 * contract, run against YAML and PostgreSQL alike.
 *
 * Budget rows have no sort field, so their tie-break is insertion order — the
 * YAML file's list order, the table's {@code seq}. Two rows on the same date
 * must list the same way in both stores, including after a replaceAll has
 * reordered them, or the Budget tab would shuffle when the store changed.
 */
public abstract class BudgetRepositoryContract extends TripScopedRepositoryContract<BudgetItem> {

    /** Never stored anywhere: the legacy destination-id holder exists only to read old YAML. */
    private static final String[] UNSTORED = {"legacyDestinationIds"};

    private static final LocalDate OCT_25 = LocalDate.of(2026, 10, 25);
    private static final LocalDate OCT_26 = LocalDate.of(2026, 10, 26);

    protected abstract BudgetRepository store();

    @Override
    protected TripScopedRepository<BudgetItem> repository() {
        return store();
    }

    protected static String tripIdOf(String slug) {
        return "trip-" + slug;
    }

    @Override
    protected BudgetItem entity(String id, String label) {
        BudgetItem item = new BudgetItem();
        item.setId(id);
        item.setTripId(tripIdOf(TRIP));
        item.setDescription(label);
        return item;
    }

    @Override
    protected String idOf(BudgetItem entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(BudgetItem entity) {
        return entity.getDescription();
    }

    // ---- ordering and finders ----------------------------------------------

    @Test
    void datedFirstUndatedLastAndInsertionOrderWithinADate() {
        store().save(TRIP, on("undated-1", null));
        store().save(TRIP, on("26-a", OCT_26));
        store().save(TRIP, on("25-a", OCT_25));
        store().save(TRIP, on("undated-2", null));
        store().save(TRIP, on("25-b", OCT_25));
        store().save(TRIP, on("26-b", OCT_26));

        assertThat(store().findAllOrdered(TRIP)).extracting(BudgetItem::getId)
                .containsExactly("25-a", "25-b", "26-a", "26-b", "undated-1", "undated-2");
    }

    @Test
    void editingARowKeepsItsPlaceAmongItsDate() {
        store().saveAll(TRIP, List.of(on("a", OCT_25), on("b", OCT_25), on("c", OCT_25)));

        BudgetItem edited = on("a", OCT_25);
        edited.setAmount(new BigDecimal("12.00"));
        store().save(TRIP, edited);

        assertThat(store().findAllOrdered(TRIP)).extracting(BudgetItem::getId).containsExactly("a", "b", "c");
    }

    @Test
    void replaceAllsOrderBecomesTheTieBreak() {
        store().saveAll(TRIP, List.of(on("a", OCT_25), on("b", OCT_25), on("undated-x", null),
                on("c", OCT_25), on("undated-y", null)));

        store().replaceAll(TRIP, List.of(on("undated-y", null), on("c", OCT_25), on("a", OCT_25),
                on("undated-x", null), on("b", OCT_25)));
        store().save(TRIP, on("d", OCT_25));

        assertThat(store().findAllOrdered(TRIP)).extracting(BudgetItem::getId)
                .containsExactly("c", "a", "b", "d", "undated-y", "undated-x");
    }

    @Test
    void findByItineraryItemIsTheFirstRowInInsertionOrder() {
        BudgetItem later = on("later-date-saved-first", OCT_26);
        later.setItineraryItemId("itin-1");
        BudgetItem earlier = on("earlier-date-saved-second", OCT_25);
        earlier.setItineraryItemId("itin-1");
        BudgetItem manual = on("manual", OCT_25);
        store().saveAll(TRIP, List.of(manual, later, earlier));

        assertThat(store().findByItineraryItem(TRIP, "itin-1")).map(BudgetItem::getId)
                .contains("later-date-saved-first");
        assertThat(store().findByItineraryItem(TRIP, "nothing")).isEmpty();
        assertThat(store().findByItineraryItem(OTHER_TRIP, "itin-1")).isEmpty();

        store().replaceAll(TRIP, List.of(earlier, manual, later));
        assertThat(store().findByItineraryItem(TRIP, "itin-1")).map(BudgetItem::getId)
                .contains("earlier-date-saved-second");
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void everyFieldComesBackAsItWasSaved() {
        BudgetItem full = fullyPopulated();
        EveryField.assertEverySet(full, UNSTORED);

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .ignoringFields(UNSTORED)
                .isEqualTo(full);
    }

    @Test
    void aRowWithNothingButItsIdComesBackThatWay() {
        BudgetItem bare = new BudgetItem();
        bare.setId("bare");
        bare.setTripId(tripIdOf(TRIP));

        store().save(TRIP, bare);

        BudgetItem loaded = store().findById(TRIP, "bare").orElseThrow();
        assertThat(loaded).usingRecursiveComparison().ignoringFields(UNSTORED).isEqualTo(bare);
        // No status is a charge; no sharers is the whole trip.
        assertThat(loaded.getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(loaded.getSharedByUserIds()).isEmpty();
        assertThat(loaded.getConfirmedAt()).isNull();
    }

    @Test
    void aNullStatusAndCategoryReadBackAsACharge() {
        BudgetItem item = entity("x", "Taxi");
        item.setStatus(null);
        item.setCategory(null);

        store().save(TRIP, item);

        BudgetItem loaded = store().findById(TRIP, "x").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BudgetStatus.CONFIRMED);
        assertThat(loaded.getCategory()).isEqualTo(ChecklistCategory.OTHERS);
    }

    @Test
    void aPendingRowStaysPendingWithNoConfirmationDate() {
        BudgetItem item = entity("x", "Inca Trail balance");
        item.markCharged(false, Instant.parse("2026-09-01T00:00:00Z"));

        store().save(TRIP, item);

        BudgetItem loaded = store().findById(TRIP, "x").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BudgetStatus.PENDING);
        assertThat(loaded.getConfirmedAt()).isNull();
    }

    @Test
    void sharersKeepTheirOrderBecauseTheOrderDecidesTheOddCent() {
        BudgetItem item = entity("x", "Dinner for three");
        item.setAmount(new BigDecimal("100.00"));
        item.setSharedByUserIds(List.of("user-zoe", "user-ana", "user-max"));

        store().save(TRIP, item);

        assertThat(store().findById(TRIP, "x").orElseThrow().getSharedByUserIds())
                .containsExactly("user-zoe", "user-ana", "user-max");
    }

    @Test
    void amountsComeBackExactlyScaleIncluded() {
        List<String> amounts = List.of("246.22", "0.1", "10.50", "1234.5678", "0", "-15.75", "99999999.99");
        for (int i = 0; i < amounts.size(); i++) {
            BudgetItem item = entity("amount-" + i, "amount " + amounts.get(i));
            item.setAmount(new BigDecimal(amounts.get(i)));
            store().save(TRIP, item);
        }

        for (int i = 0; i < amounts.size(); i++) {
            assertThat(store().findById(TRIP, "amount-" + i).orElseThrow().getAmount())
                    .isEqualTo(new BigDecimal(amounts.get(i)))   // equals, so scale must match too
                    .hasToString(amounts.get(i));
        }
    }

    private BudgetItem on(String id, LocalDate date) {
        BudgetItem item = entity(id, id);
        item.setDate(date);
        return item;
    }

    static BudgetItem fullyPopulated() {
        BudgetItem item = new BudgetItem();
        item.setId("budget-casa-san-blas");
        item.setTripId(tripIdOf(TRIP));
        item.setItineraryItemId("itin-night-1");
        item.setCategory(ChecklistCategory.LODGING);
        item.setDescription("Casa San Blas, 6 nights");
        item.setNote("Breakfast included, ask for a room away from the street.");
        item.setAmount(new BigDecimal("246.22"));
        item.setCurrency("PEN");
        item.setDate(LocalDate.of(2026, 10, 25));
        item.setCountryCodes(List.of("PE", "BO"));
        item.setSharedByUserIds(List.of("user-zoe", "user-ana"));
        item.setPaidByUserId("user-ana");
        // Pending *and* a confirmation date is a state markCharged never makes;
        // it is here because every field must differ from its default, and a
        // repository stores what it is given rather than second-guessing it.
        item.setStatus(BudgetStatus.PENDING);
        item.setConfirmedAt(Instant.parse("2026-09-05T09:30:00.654321Z"));
        item.setCreatedAt(Instant.parse("2026-09-01T10:15:30.123456Z"));
        item.setCreatedByUserId("user-ana");
        item.setUpdatedAt(Instant.parse("2026-09-02T08:00:00Z"));
        item.setUpdatedByUserId("user-ben");
        return item;
    }
}
