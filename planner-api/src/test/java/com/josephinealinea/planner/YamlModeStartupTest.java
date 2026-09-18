package com.josephinealinea.planner;

import com.josephinealinea.planner.budget.infra.BudgetRepository;
import com.josephinealinea.planner.budget.infra.YamlBudgetRepository;
import com.josephinealinea.planner.checklist.infra.ChecklistRepository;
import com.josephinealinea.planner.checklist.infra.YamlChecklistRepository;
import com.josephinealinea.planner.config.FeatureFlags;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.destinations.infra.YamlDestinationRepository;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.itinerary.infra.YamlItineraryRepository;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.rates.infra.YamlRatesRepository;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import com.josephinealinea.planner.weather.infra.YamlWeatherRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole application context, booted the way a laptop boots it: the
 * database flag off, every record in YAML.
 *
 * Nothing else in the suite starts Spring — every other test builds its
 * services by hand — so without this a dependency that changes what Spring
 * Boot auto-configures would pass every test and still fail to start. That is
 * exactly what adding the JDBC starter does: it makes Boot build a DataSource,
 * which dies with "url not specified" on an install that has none. This test
 * was written, and passed, before that dependency was added, and failed the
 * moment it was added without DatabaseModeEnvironment — so it is the proof
 * that YAML mode came through unchanged.
 *
 * It needs no network: the rates provider is pointed at a port nothing listens
 * on, so the startup fetch (asynchronous, and failure-tolerant by design)
 * fails at once instead of racing the temp directory's cleanup with a write.
 */
@SpringBootTest(properties = "feature-enable-database=false")
@AutoConfigureMockMvc
class YamlModeStartupTest {

    // The flag is set above rather than below on purpose: DatabaseModeEnvironment
    // reads it while the environment is being prepared, before any
    // @DynamicPropertySource value has been added — so a flag set there would
    // choose the repositories but not the auto-configurations.

    @TempDir
    static Path data;

    @DynamicPropertySource
    static void yamlMode(DynamicPropertyRegistry registry) {
        registry.add("app.storage.root", () -> data.resolve("store").toString());
        registry.add("app.publish.dir", () -> data.resolve("published").toString());
        registry.add("app.rates.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.mail.mode", () -> "log");
        registry.add("app.bootstrap.owner-email", () -> "owner@example.com");
        registry.add("app.bootstrap.owner-password", () -> "password123");
    }

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Test
    void startsOnTheYamlStoreWithNoDatabase() {
        assertThat(context.getBean(FeatureFlags.class).databaseEnabled()).isFalse();
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.containsBean("flyway")).isFalse();
    }

    @Test
    void everyRepositoryIsTheYamlOne() {
        assertThat(context.getBean(BudgetRepository.class)).isInstanceOf(YamlBudgetRepository.class);
        assertThat(context.getBean(ChecklistRepository.class)).isInstanceOf(YamlChecklistRepository.class);
        assertThat(context.getBean(DestinationRepository.class)).isInstanceOf(YamlDestinationRepository.class);
        assertThat(context.getBean(ItineraryRepository.class)).isInstanceOf(YamlItineraryRepository.class);
        assertThat(context.getBean(WeatherRepository.class)).isInstanceOf(YamlWeatherRepository.class);
        assertThat(context.getBean(RatesRepository.class)).isInstanceOf(YamlRatesRepository.class);
    }

    @Test
    void writesItsFilesUnderTheConfiguredRoot() {
        // The bootstrap owner is the one record every start writes.
        assertThat(Files.exists(data.resolve("store").resolve("users.yml"))).isTrue();
    }

    @Test
    void reportsHealthyWithoutADatabaseIndicator() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
