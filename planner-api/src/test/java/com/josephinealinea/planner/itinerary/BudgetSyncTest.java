package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The cost-to-budget cascade, exercised against the real file store. */
class BudgetSyncTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String USER_ID = "user-1";

    private BudgetRepository budget;
    private BudgetSync sync;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        budget = new BudgetRepository(new YamlStore(), new YamlPaths(props), new TripLocks());
        sync = new BudgetSync(budget);
    }

    private static ItineraryItem plan(BigDecimal cost, String currency) {
        ItineraryItem plan = new ItineraryItem();
        plan.setId("plan-1");
        plan.setTripId("trip-1");
        plan.setCategory(ChecklistCategory.TRANSPORTATION);
        plan.setDescription("Avianca one way CUZ to LPB");
        plan.setStartAt(LocalDateTime.of(2026, 11, 1, 9, 30));
        plan.setCost(cost);
        plan.setCurrency(currency);
        return plan;
    }

    @Test
    void aCostCreatesAMatchingBudgetRecord() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);

        assertThat(item.getBudgetItemId()).isNotNull();
        BudgetItem created = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        assertThat(created.getAmount()).isEqualByComparingTo("246.22");
        assertThat(created.getCurrency()).isEqualTo("USD");
        assertThat(created.getCategory()).isEqualTo(ChecklistCategory.TRANSPORTATION);
        assertThat(created.getDescription()).isEqualTo("Avianca one way CUZ to LPB");
        assertThat(created.getDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(created.getItineraryItemId()).isEqualTo("plan-1");
        assertThat(created.isFromPlan()).isTrue();
    }

    @Test
    void changingTheCostUpdatesTheAmountButNotTheEditedDescription() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);

        // Somebody tidies up the budget row by hand.
        BudgetItem edited = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        edited.setDescription("Flight to La Paz");
        edited.setCategory(ChecklistCategory.OTHERS);
        budget.save(SLUG, edited);

        item.setCost(new BigDecimal("300.00"));
        item.setCurrency("EUR");
        sync.afterSave(SLUG, item, USER_ID, null);

        BudgetItem after = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo("300.00");
        assertThat(after.getCurrency()).isEqualTo("EUR");
        // The hand edits survive.
        assertThat(after.getDescription()).isEqualTo("Flight to La Paz");
        assertThat(after.getCategory()).isEqualTo(ChecklistCategory.OTHERS);
    }

    @Test
    void clearingTheCostRemovesTheBudgetRecord() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);
        String budgetId = item.getBudgetItemId();

        item.setCost(null);
        item.setCurrency(null);
        sync.afterSave(SLUG, item, USER_ID, null);

        assertThat(budget.findById(SLUG, budgetId)).isEmpty();
        assertThat(item.getBudgetItemId()).isNull();
    }

    @Test
    void aZeroCostIsTreatedAsNoCost() {
        ItineraryItem item = plan(BigDecimal.ZERO, "USD");
        sync.afterSave(SLUG, item, USER_ID, null);

        assertThat(item.getBudgetItemId()).isNull();
        assertThat(budget.findAll(SLUG)).isEmpty();
    }

    @Test
    void deletingThePlanRemovesTheBudgetRecordItCreated() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);
        String budgetId = item.getBudgetItemId();

        sync.afterDelete(SLUG, item);
        assertThat(budget.findById(SLUG, budgetId)).isEmpty();
    }

    @Test
    void aManualExpenseIsNeverTouched() {
        BudgetItem manual = new BudgetItem();
        manual.setId("manual-1");
        manual.setTripId("trip-1");
        manual.setDescription("Yellow fever vaccine");
        manual.setAmount(new BigDecimal("10.35"));
        manual.setCurrency("EUR");
        budget.save(SLUG, manual);

        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);
        sync.afterDelete(SLUG, item);

        assertThat(budget.findById(SLUG, "manual-1")).isPresent();
        assertThat(budget.findById(SLUG, "manual-1").orElseThrow().isFromPlan()).isFalse();
    }

    @Test
    void aCostCreatesABudgetRecordCarryingThePlansLocations() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        item.setCountryCodes(List.of("dest-1", "dest-2"));
        sync.afterSave(SLUG, item, USER_ID, null);

        BudgetItem created = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        assertThat(created.getCountryCodes()).containsExactly("dest-1", "dest-2");
    }

    /**
     * The one-way, narrow sync rule applies to locations too: a later cost
     * change never overwrites locations someone has since corrected on the
     * budget row, exactly like it never overwrites the description or
     * category.
     */
    @Test
    void changingTheCostNeverOverwritesLocationsEditedOnTheBudgetRow() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        item.setCountryCodes(List.of("dest-1"));
        sync.afterSave(SLUG, item, USER_ID, null);

        // Somebody corrects the row's locations by hand on the budget tab.
        BudgetItem edited = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        edited.setCountryCodes(List.of("dest-2"));
        budget.save(SLUG, edited);

        // The plan itself still (or now) points somewhere else entirely.
        item.setCost(new BigDecimal("300.00"));
        item.setCountryCodes(List.of("dest-3"));
        sync.afterSave(SLUG, item, USER_ID, null);

        BudgetItem after = budget.findById(SLUG, item.getBudgetItemId()).orElseThrow();
        assertThat(after.getAmount()).isEqualByComparingTo("300.00");
        // The hand-edited locations survive; the plan's own change is not applied.
        assertThat(after.getCountryCodes()).containsExactly("dest-2");
    }

    @Test
    void aBudgetRowWhoseBackLinkWasLostIsStillCleanedUp() {
        ItineraryItem item = plan(new BigDecimal("246.22"), "USD");
        sync.afterSave(SLUG, item, USER_ID, null);

        // Simulate the plan losing its back-link but the row still pointing at it.
        item.setBudgetItemId(null);
        sync.afterDelete(SLUG, item);

        assertThat(budget.findByItineraryItem(SLUG, "plan-1")).isEmpty();
    }
}
