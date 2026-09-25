package com.josephinealinea.planner;

import com.josephinealinea.planner.budget.api.BudgetService;
import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.JdbcBudgetRepository;
import com.josephinealinea.planner.checklist.domain.ChecklistCategory;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.JdbcChecklistRepository;
import com.josephinealinea.planner.config.FeatureFlags;
import com.josephinealinea.planner.destinations.api.DestinationService;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.JdbcDestinationRepository;
import com.josephinealinea.planner.identity.infra.JdbcUserRepository;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.JdbcItineraryRepository;
import com.josephinealinea.planner.rates.infra.JdbcRatesRepository;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.storage.jdbc.PostgresTest;
import com.josephinealinea.planner.storage.jdbc.PostgresTestDatabase;
import com.josephinealinea.planner.trips.api.TripService;
import com.josephinealinea.planner.trips.api.TripViewAssembler;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.infra.JdbcTripRepository;
import com.josephinealinea.planner.trips.infra.TripRepository;
import com.josephinealinea.planner.weather.infra.JdbcWeatherRepository;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application, started in database mode — the counterpart of
 * YamlModeStartupTest, and the first test that can exist only once every
 * repository has a JDBC implementation.
 *
 * The repository contract tests prove each store in isolation. What they
 * cannot prove is that the real application wires together on them: that
 * every service finds a JDBC bean for every interface, that Flyway has run
 * before the bootstrap owner is written, that a cascade crossing four
 * repositories works on real rows. That is what this is for.
 *
 * It gets a database of its own rather than the shared test one, because
 * PostgresTestDatabase empties every table before each test and this context
 * outlives a single test.
 */
@SpringBootTest(properties = "feature-enable-database=true")
@AutoConfigureMockMvc
@PostgresTest
class DatabaseModeApplicationTest {

    // The flag is set above rather than below for the reason YamlModeStartupTest
    // gives: DatabaseModeEnvironment reads it before any @DynamicPropertySource
    // value exists.

    private static final String DATABASE = "appcheck";

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void databaseMode(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.start();
        JdbcClient admin = PostgresTestDatabase.jdbc();
        admin.sql("DROP DATABASE IF EXISTS " + DATABASE).update();
        admin.sql("CREATE DATABASE " + DATABASE).update();
        var pg = PostgresTestDatabase.container();

        registry.add("spring.datasource.url", () -> pg.getJdbcUrl().replace("/planner", "/" + DATABASE));
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        // Files the app still writes in database mode: the rendered pages
        // (filesystem page store) and, with no JWT_SECRET, the generated key.
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("app.bootstrap.owner-email", () -> "owner@example.com");
        registry.add("app.bootstrap.owner-password", () -> "password123");
    }

    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired UserRepository users;
    @Autowired TripService trips;
    @Autowired DestinationService destinations;
    @Autowired BudgetService budget;
    @Autowired TripRepository tripRepository;
    @Autowired TripViewAssembler views;

    @Test
    void startsOnPostgresWithFlywayApplied() {
        assertThat(context.getBean(FeatureFlags.class).databaseEnabled()).isTrue();
        assertThat(jdbc.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void everyRepositoryIsTheJdbcOne() {
        assertThat(context.getBean(UserRepository.class)).isInstanceOf(JdbcUserRepository.class);
        assertThat(context.getBean(TripRepository.class)).isInstanceOf(JdbcTripRepository.class);
        assertThat(context.getBean(DestinationRepository.class)).isInstanceOf(JdbcDestinationRepository.class);
        assertThat(context.getBean(ChecklistRepository.class)).isInstanceOf(JdbcChecklistRepository.class);
        assertThat(context.getBean(ItineraryRepository.class)).isInstanceOf(JdbcItineraryRepository.class);
        assertThat(context.getBean(BudgetRepository.class)).isInstanceOf(JdbcBudgetRepository.class);
        assertThat(context.getBean(WeatherRepository.class)).isInstanceOf(JdbcWeatherRepository.class);
        assertThat(context.getBean(RatesRepository.class)).isInstanceOf(JdbcRatesRepository.class);
    }

    /** The one record every start writes, written after Flyway has made its table. */
    @Test
    void theBootstrapOwnerIsARowInPostgres() {
        assertThat(jdbc.sql("SELECT count(*) FROM users WHERE email = 'owner@example.com'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void reportsHealthy() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * The cascade from CLAUDE.md, end to end on real rows: a dated destination
     * seeds its three checklist items, an expense lands in the budget, the
     * trip view assembles from all of it — and deleting the trip leaves
     * nothing behind in any table.
     */
    @Test
    void aTripLivesAndDiesInPostgres() {
        String owner = users.findByEmail("owner@example.com").orElseThrow().getId();
        Trip trip = trips.create(owner, "Database Mode Check",
                LocalDate.parse("2026-10-24"), LocalDate.parse("2026-11-08"));

        destinations.create(trip.getId(), owner, new DestinationService.Input(
                "Cusco", "PE", -13.53, -71.97, null, "America/Lima",
                LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-31"), null, null));
        budget.create(trip.getId(), owner, new BudgetService.Input(
                "Airbnb in Cusco", ChecklistCategory.LODGING, new BigDecimal("246.22"), "EUR",
                LocalDate.parse("2026-10-25"), List.of("PE"), List.of(owner), owner, true));

        assertThat(count("trips", trip.getId())).isEqualTo(1);
        assertThat(count("trip_members", trip.getId())).isEqualTo(1);
        assertThat(count("destinations", trip.getId())).isEqualTo(1);
        // Six nights in Cusco: transport, activities and accommodation.
        assertThat(count("checklist_items", trip.getId())).isEqualTo(3);
        assertThat(count("budget_items", trip.getId())).isEqualTo(1);

        var detail = views.detail(tripRepository.findById(trip.getId()).orElseThrow(), owner);
        assertThat(detail.destinations()).extracting("name").containsExactly("Cusco");
        assertThat(detail.checklist()).hasSize(3);
        assertThat(detail.budget().items()).singleElement()
                .satisfies(item -> assertThat(item.getAmount()).isEqualByComparingTo("246.22"));

        trips.delete(trip.getId(), owner);

        for (String table : List.of("trips", "trip_members", "destinations", "checklist_items", "budget_items")) {
            assertThat(count(table, trip.getId())).as(table + " after delete").isZero();
        }
    }

    private long count(String table, String tripId) {
        String column = table.equals("trips") ? "id" : "trip_id";
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :trip")
                .param("trip", tripId).query(Long.class).single();
    }
}
