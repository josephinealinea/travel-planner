package com.josephinealinea.planner.itinerary;

import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stay is written out as one entry per day it covers, at create time.
 *
 * The point is that each night is then an ordinary itinerary entry: it can be
 * selected, edited and — the reason this exists — deleted on its own, without
 * disturbing the rest of the booking. The alternative, expanding one stored
 * row across days while rendering, made every night share the one row's
 * identity, so a single tick appeared to select the whole stay and there was
 * nothing to delete but the lot.
 *
 * What must not follow from splitting it up: four charges for one booking.
 */
class StayNightsTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String USER_ID = "user-1";

    private ItineraryService service;
    private ItineraryRepository itinerary;
    private BudgetRepository budget;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(dir.resolve("published").toString(), "http://localhost:8080/p"),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(null, null, null));

        var store = new com.josephinealinea.planner.storage.YamlStore();
        var paths = new com.josephinealinea.planner.storage.YamlPaths(props);
        var locks = new com.josephinealinea.planner.storage.TripLocks();

        itinerary = new ItineraryRepository(store, paths, locks);
        budget = new BudgetRepository(store, paths, locks);
        ChecklistRepository checklist = new ChecklistRepository(store, paths, locks);
        DestinationRepository destinations = new DestinationRepository(store, paths, locks); TripCountries tripCountries = new TripCountries(destinations);
        TripRepository trips = new YamlTripRepository(store, paths, locks);

        Trip trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.setStartDate(LocalDate.parse("2026-10-24"));
        trip.setEndDate(LocalDate.parse("2026-11-08"));
        trip.setDisplayCurrency("USD");
        trip.getMembers().add(new TripMember(USER_ID, "member@example.com", TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        service = new ItineraryService(itinerary, destinations,
                new ChecklistService(checklist, itinerary, access, tripCountries), access,
                new PlanTemplates(), new BudgetSync(budget), new CountryCatalog(RestClient.create()), tripCountries);
    }

    /** Check-in 25 Oct 15:00, check-out 28 Oct 11:00 — three nights. */
    private ItineraryItem createThreeNightStay() {
        return service.create(TRIP_ID, USER_ID, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hotel in Cusco", LocalDateTime.of(2026, 10, 25, 15, 0), LocalDateTime.of(2026, 10, 28, 11, 0), null, new BigDecimal("240.00"), "USD", null, List.of()));
    }

    private List<ItineraryItem> stored() {
        return itinerary.findAll(SLUG).stream()
                .sorted(Comparator.comparing(ItineraryItem::getStartAt))
                .toList();
    }

    @Test
    void aThreeNightStayIsStoredAsFourEntries() {
        createThreeNightStay();

        // Four days, not three: you are still in the room on the morning you
        // check out.
        assertThat(stored())
                .extracting(item -> item.getStartAt().toLocalDate().toString())
                .containsExactly("2026-10-25", "2026-10-26", "2026-10-27", "2026-10-28");
    }

    @Test
    void onlyTheEndsOfTheStayCarryATime() {
        createThreeNightStay();
        List<ItineraryItem> nights = stored();

        assertThat(nights.get(0).getStartAt().toLocalTime().toString()).isEqualTo("15:00");
        assertThat(nights.get(0).coversWholeDay()).isFalse();

        // The nights between belong to a day, not to an hour.
        assertThat(nights.get(1).coversWholeDay()).isTrue();
        assertThat(nights.get(2).coversWholeDay()).isTrue();

        // Check-out keeps the hour you have to be out by.
        assertThat(nights.get(3).getStartAt().toLocalTime().toString()).isEqualTo("11:00");
        assertThat(nights.get(3).coversWholeDay()).isFalse();
    }

    @Test
    void oneBookingStillProducesOneCharge() {
        createThreeNightStay();

        assertThat(stored()).filteredOn(ItineraryItem::hasCost).hasSize(1);
        assertThat(budget.findAll(SLUG)).hasSize(1);
        assertThat(budget.findAll(SLUG).get(0).getAmount()).isEqualByComparingTo("240.00");
    }

    @Test
    void anyOneNightCanBeDeletedWithoutTheRest() {
        createThreeNightStay();
        ItineraryItem lastNight = stored().get(3);

        service.delete(TRIP_ID, USER_ID, lastNight.getId());

        assertThat(stored())
                .extracting(item -> item.getStartAt().toLocalDate().toString())
                .containsExactly("2026-10-25", "2026-10-26", "2026-10-27");
        // Deleting a night that never carried the cost leaves the charge alone.
        assertThat(budget.findAll(SLUG)).hasSize(1);
    }

    @Test
    void aMiddleNightCanGoToo() {
        createThreeNightStay();

        service.delete(TRIP_ID, USER_ID, stored().get(1).getId());

        assertThat(stored())
                .extracting(item -> item.getStartAt().toLocalDate().toString())
                .containsExactly("2026-10-25", "2026-10-27", "2026-10-28");
    }

    @Test
    void onlyTheCheckInRowCountsAsThePlan() {
        ItineraryItem plan = createThreeNightStay();

        // The whole point of planId: four days, one plan. Anything counting
        // plans against a checklist item counts owners, not days.
        assertThat(stored()).hasSize(4);
        assertThat(stored()).filteredOn(ItineraryItem::ownsItsPlan).hasSize(1);
        assertThat(stored().stream().filter(ItineraryItem::ownsItsPlan).findFirst().orElseThrow()
                .getId()).isEqualTo(plan.getId());
        assertThat(stored()).filteredOn(item -> !item.ownsItsPlan())
                .allMatch(item -> plan.getId().equals(item.getPlanId()));
    }

    @Test
    void twoPlansOnOneChecklistItemStayTwoPlans() {
        // "Plan another" against the same checklist item: 1N then 2N.
        service.create(TRIP_ID, USER_ID, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Airbnb in Cusco", LocalDateTime.of(2026, 10, 27, 15, 0), LocalDateTime.of(2026, 10, 28, 11, 0), null, new BigDecimal("150.00"), "EUR", null, List.of()));
        service.create(TRIP_ID, USER_ID, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Another airbnb in Cusco", LocalDateTime.of(2026, 10, 28, 15, 0), LocalDateTime.of(2026, 10, 30, 11, 0), null, new BigDecimal("200.00"), "EUR", null, List.of()));

        // 2 + 3 days on the itinerary, but two plans and two charges.
        assertThat(stored()).hasSize(5);
        assertThat(stored()).filteredOn(ItineraryItem::ownsItsPlan).hasSize(2);
        assertThat(budget.findAll(SLUG)).hasSize(2);
    }

    @Test
    void deletingThePlansOwnRowPromotesTheNextDay() {
        ItineraryItem plan = createThreeNightStay();

        service.delete(TRIP_ID, USER_ID, plan.getId());

        // Still exactly one owner, so the plan is still countable — the days
        // are not left pointing at a record that no longer exists.
        List<ItineraryItem> left = stored();
        assertThat(left).hasSize(3);
        assertThat(left).filteredOn(ItineraryItem::ownsItsPlan).hasSize(1);
        ItineraryItem heir = left.get(0);
        assertThat(heir.ownsItsPlan()).isTrue();
        assertThat(left.subList(1, left.size()))
                .allMatch(item -> heir.getId().equals(item.getPlanId()));
        // The charge went with the row that carried it.
        assertThat(budget.findAll(SLUG)).isEmpty();
    }

    @Test
    void deletingThePlanTakesEveryDayWithIt() {
        ItineraryItem plan = createThreeNightStay();

        int removed = service.deletePlan(TRIP_ID, USER_ID, plan.getId());

        assertThat(removed).isEqualTo(4);
        assertThat(stored()).isEmpty();
        assertThat(budget.findAll(SLUG)).isEmpty();
    }

    @Test
    void deletingOneDayLeavesTheRestOfThePlanOwned() {
        ItineraryItem plan = createThreeNightStay();

        service.delete(TRIP_ID, USER_ID, stored().get(2).getId());

        assertThat(stored()).hasSize(3);
        assertThat(stored()).filteredOn(ItineraryItem::ownsItsPlan).hasSize(1);
        assertThat(stored().get(0).getId()).isEqualTo(plan.getId());
    }

    @Test
    void aSingleNightStayIsJustOneEntry() {
        // Check-in and check-out on consecutive days is one night, so two
        // entries — the arrival and the morning out. Nothing in between.
        service.create(TRIP_ID, USER_ID, new ItineraryService.Input(null, ChecklistCategory.LODGING, "Hostel in Lima", LocalDateTime.of(2026, 10, 24, 20, 0), LocalDateTime.of(2026, 10, 25, 10, 0), null, null, null, null, List.of()));

        assertThat(stored()).hasSize(2);
        assertThat(stored()).noneMatch(ItineraryItem::coversWholeDay);
    }

    @Test
    void anEntryThatIsNotLodgingIsNeverSpread() {
        // A long flight is one event, however many days it crosses.
        service.create(TRIP_ID, USER_ID, new ItineraryService.Input(null, ChecklistCategory.TRANSPORTATION, "Flight LAX to LIM", LocalDateTime.of(2026, 10, 24, 22, 0), LocalDateTime.of(2026, 10, 25, 8, 0), null, null, null, null, List.of()));

        assertThat(stored()).hasSize(1);
        assertThat(stored().get(0).getEndAt()).isEqualTo(LocalDateTime.of(2026, 10, 25, 8, 0));
    }

    // ── a date with no time ─────────────────────────

    /**
     * An entry given a date but no time belongs to a day, not an hour.
     *
     * It records that with the same `allDay` flag a stay's middle nights use,
     * so both render as "—" and nothing has to special-case a manual entry.
     * Without it an empty time field became midnight and the row read "00:00",
     * which looks like a stated departure time rather than a blank.
     */
    @Test
    void anEntryWithNoTimeIsMarkedAllDay() {
        ItineraryItem entry = service.create(TRIP_ID, USER_ID, new ItineraryService.Input(
                null, ChecklistCategory.FOOD, "Dinner somewhere",
                LocalDateTime.parse("2026-10-26T00:00"), null, true,
                null, null, null, null));

        assertThat(entry.coversWholeDay()).isTrue();
        assertThat(itinerary.findById(SLUG, entry.getId()).orElseThrow().coversWholeDay()).isTrue();
    }

    @Test
    void anEntryWithATimeKeepsIt() {
        ItineraryItem entry = service.create(TRIP_ID, USER_ID, new ItineraryService.Input(
                null, ChecklistCategory.SHOPPING, "Market run",
                LocalDateTime.parse("2026-10-26T09:30"), null, false,
                null, null, null, null));

        assertThat(entry.coversWholeDay()).isFalse();
        assertThat(entry.getStartAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 30));
    }

    /** Adding a time later clears the flag, so the row starts showing it. */
    @Test
    void givingAnAllDayEntryATimeClearsTheFlag() {
        ItineraryItem entry = service.create(TRIP_ID, USER_ID, new ItineraryService.Input(
                null, ChecklistCategory.FOOD, "Dinner somewhere",
                LocalDateTime.parse("2026-10-26T00:00"), null, true,
                null, null, null, null));
        assertThat(entry.coversWholeDay()).isTrue();

        service.update(TRIP_ID, USER_ID, entry.getId(), new ItineraryService.Input(
                null, null, null, LocalDateTime.parse("2026-10-26T19:00"), null, false,
                null, null, null, null));

        assertThat(itinerary.findById(SLUG, entry.getId()).orElseThrow().coversWholeDay()).isFalse();
    }

    /** Null means "leave it": a patch that says nothing about time must not clear it. */
    @Test
    void aPatchThatSaysNothingAboutTimeLeavesTheFlagAlone() {
        ItineraryItem entry = service.create(TRIP_ID, USER_ID, new ItineraryService.Input(
                null, ChecklistCategory.FOOD, "Dinner somewhere",
                LocalDateTime.parse("2026-10-26T00:00"), null, true,
                null, null, null, null));

        service.update(TRIP_ID, USER_ID, entry.getId(), new ItineraryService.Input(
                null, null, "Dinner at the market", null, null, null, null, null, null, null));

        ItineraryItem saved = itinerary.findById(SLUG, entry.getId()).orElseThrow();
        assertThat(saved.getDescription()).isEqualTo("Dinner at the market");
        assertThat(saved.coversWholeDay()).isTrue();
    }
}
