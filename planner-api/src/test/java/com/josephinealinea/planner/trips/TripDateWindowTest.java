package com.josephinealinea.planner.trips;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.domain.BudgetItem;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.checklist.api.ChecklistService;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.destinations.api.ChecklistSeeder;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.api.TripCountries;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.geocoding.CountryCatalog;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.itinerary.api.BudgetSync;
import com.josephinealinea.planner.itinerary.api.ItineraryService;
import com.josephinealinea.planner.itinerary.api.PlanTemplates;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.trips.infra.YamlTripRepository;
import com.josephinealinea.planner.rates.TestRates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The trip's dates bound every date recorded against it — a destination's stay,
 * an itinerary entry, the Plan form's start and end (which is an itinerary
 * entry), and an expense's date. One rule, four surfaces, so it is pinned down
 * in one place rather than three-quarters of the way round the cascade.
 *
 * The deliberate omission is retro-validation: narrowing a trip's own dates
 * does not reject or rewrite what is already stored, and does not make those
 * rows uneditable — only a date the caller actually sends is checked.
 */
class TripDateWindowTest {

    private static final String SLUG = "latam-trip-2026";
    private static final String TRIP_ID = "trip-1";
    private static final String USER_ID = "user-1";

    private static final LocalDate TRIP_START = LocalDate.parse("2026-10-20");
    private static final LocalDate TRIP_END = LocalDate.parse("2026-11-05");

    private DestinationService destinationService;
    private ItineraryService itineraryService;
    private BudgetService budgetService;
    private DestinationRepository destinations;
    private BudgetRepository budgetRepository;
    private TripRepository trips;
    private Trip trip;

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

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        TripLocks locks = new TripLocks();

        destinations = new DestinationRepository(store, paths, locks);

        TripCountries tripCountries = new TripCountries(destinations);
        ChecklistRepository checklistRepository = new ChecklistRepository(store, paths, locks);
        ItineraryRepository itineraryRepository = new ItineraryRepository(store, paths, locks);
        budgetRepository = new BudgetRepository(store, paths, locks);
        UserRepository users = new YamlUserRepository(store, paths, locks);
        trips = new YamlTripRepository(store, paths, locks);

        trip = new Trip();
        trip.setId(TRIP_ID);
        trip.setSlug(SLUG);
        trip.setTitle("LATAM Trip 2026");
        trip.setOwnerUserId(USER_ID);
        trip.setStartDate(TRIP_START);
        trip.setEndDate(TRIP_END);
        trip.getMembers().add(new TripMember(USER_ID, "member@example.com", TripRole.OWNER, null));
        trips.save(trip);

        TripAccessService access = new TripAccessService(trips);
        ChecklistService checklistService =
                new ChecklistService(checklistRepository, itineraryRepository, access, tripCountries);

        destinationService = new DestinationService(destinations, checklistRepository, itineraryRepository,
                budgetRepository, new ChecklistSeeder(), access, new CountryCatalog(RestClient.create()), tripCountries);
        itineraryService = new ItineraryService(itineraryRepository, destinations, checklistService, access,
                new PlanTemplates(), new BudgetSync(budgetRepository), new CountryCatalog(RestClient.create()), tripCountries);
        budgetService = new BudgetService(budgetRepository, itineraryRepository, destinations,
                users, access, tripCountries, TestRates.empty(store, paths, props));
    }

    // ── destinations ────────────────────────────────

    private DestinationService.Input stay(String start, String end) {
        return new DestinationService.Input("Cusco", "PE", "Peru", null, null, null, null, start == null ? null : LocalDate.parse(start), end == null ? null : LocalDate.parse(end), null, null);
    }

    @Test
    void aStayInsideTheTripIsAccepted() {
        assertThatCode(() -> destinationService.create(TRIP_ID, USER_ID, stay("2026-10-25", "2026-10-31")))
                .doesNotThrowAnyException();
    }

    @Test
    void aStayStartingBeforeTheTripIsRejected() {
        assertThatThrownBy(() -> destinationService.create(TRIP_ID, USER_ID, stay("2026-10-19", "2026-10-31")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("start date")
                .hasMessageContaining("2026-10-20 to 2026-11-05");
    }

    @Test
    void aStayEndingAfterTheTripIsRejected() {
        assertThatThrownBy(() -> destinationService.create(TRIP_ID, USER_ID, stay("2026-10-25", "2026-11-06")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("end date");
    }

    @Test
    void editingADestinationLeftOutsideTheTripStillWorks() {
        // The stay was recorded, and only afterwards did the trip shrink around
        // it. Renaming it must not be blocked by a date the caller is not
        // touching, or the row becomes impossible to correct.
        destinationService.create(TRIP_ID, USER_ID, stay("2026-10-25", "2026-10-31"));
        String destinationId = destinations.findAll(SLUG).get(0).getId();

        trip.setStartDate(LocalDate.parse("2026-11-01"));
        trips.save(trip);

        DestinationService.Input renameOnly = new DestinationService.Input("Cusco (renamed)", null, null, null, null, null, null, null, null, null, null);
        Destination updated = destinationService.update(TRIP_ID, USER_ID, destinationId, renameOnly);

        assertThat(updated.getName()).isEqualTo("Cusco (renamed)");
        assertThat(updated.getStartDate()).isEqualTo(LocalDate.parse("2026-10-25"));
    }

    // ── itinerary, and the checklist's Plan form behind it ──

    private ItineraryService.Input plan(String startAt, String endAt) {
        return new ItineraryService.Input(null, ChecklistCategory.TRANSPORTATION, "Flight LIM to CUZ", startAt == null ? null : LocalDateTime.parse(startAt), endAt == null ? null : LocalDateTime.parse(endAt), null, null, null, null, null, null, List.of());
    }

    @Test
    void aPlanOnTheLastDayOfTheTripIsAccepted() {
        assertThatCode(() -> itineraryService.create(TRIP_ID, USER_ID,
                plan("2026-11-05T22:00", "2026-11-05T23:45"))).doesNotThrowAnyException();
    }

    @Test
    void aPlanRunningPastMidnightOnTheLastDayIsRejected() {
        assertThatThrownBy(() -> itineraryService.create(TRIP_ID, USER_ID,
                plan("2026-11-05T22:00", "2026-11-06T01:00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("end date");
    }

    @Test
    void movingAPlanOutsideTheTripIsRejected() {
        ItineraryItem saved = itineraryService.create(TRIP_ID, USER_ID,
                plan("2026-10-25T09:00", "2026-10-25T11:00"));

        assertThatThrownBy(() -> itineraryService.update(TRIP_ID, USER_ID, saved.getId(),
                plan("2026-09-25T09:00", "2026-09-25T11:00")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("start date");
    }

    // ── budget ──────────────────────────────────────

    private BudgetService.Input expense(String date) {
        return new BudgetService.Input("Yellow fever vaccine", ChecklistCategory.OTHERS,
                new BigDecimal("60.00"), "EUR",
                date == null ? null : LocalDate.parse(date), List.of(), null, null, null);
    }

    @Test
    void anExpenseDatedOutsideTheTripIsRejected() {
        assertThatThrownBy(() -> budgetService.create(TRIP_ID, USER_ID, expense("2026-12-01")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expense date");
    }

    @Test
    void anExpenseWithNoDateIsFine() {
        // The field is optional — a cost known before the day it lands on is
        // the ordinary case for a vaccine or a visa.
        assertThatCode(() -> budgetService.create(TRIP_ID, USER_ID, expense(null)))
                .doesNotThrowAnyException();
    }

    @Test
    void repricingAnExpenseThatCarriesNoDateIsNotBlocked() {
        budgetService.create(TRIP_ID, USER_ID, expense(null));
        BudgetItem item = budgetRepository.findAll(SLUG).get(0);

        BudgetService.Input amountOnly = new BudgetService.Input(
                null, null, new BigDecimal("75.00"), null, null, null, null, null, null);

        assertThat(budgetService.update(TRIP_ID, USER_ID, item.getId(), amountOnly).getAmount())
                .isEqualByComparingTo("75.00");
    }
}
