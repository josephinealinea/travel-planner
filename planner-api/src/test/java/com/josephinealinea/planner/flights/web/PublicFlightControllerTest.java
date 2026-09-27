package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.flights.domain.Airport;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.domain.FlightSchedule;
import com.josephinealinea.planner.flights.domain.FlightSnapshot;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.itinerary.domain.ItineraryItem;
import com.josephinealinea.planner.itinerary.infra.ItineraryRepository;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripStatus;
import com.josephinealinea.planner.trips.infra.TripRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.josephinealinea.planner.shared.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MvcResult;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public refresh endpoint through the real security filter chain and the
 * YAML stores, with no sign-in. Both flight services have no key here, so
 * nothing can reach the network: a 200 is served from a frozen (landed) cached
 * record, and a flight with no record answers 204.
 */
@SpringBootTest(properties = "feature-enable-database=false")
@AutoConfigureMockMvc
class PublicFlightControllerTest {

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
        registry.add("app.flights.aerodatabox.key", () -> "");
        registry.add("app.flights.aviationstack.key", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired TripRepository trips;
    @Autowired ItineraryRepository itinerary;
    @Autowired FlightRecordRepository records;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    private void givenTrip(String slug, TripStatus status, String number) {
        Trip trip = new Trip();
        trip.setId("id-" + slug);
        trip.setSlug(slug);
        trip.setOwnerUserId("owner");
        trip.setStatus(status);
        trips.save(trip);
        ItineraryItem item = new ItineraryItem();
        item.setId("e-" + slug);
        item.setStartAt(LocalDateTime.of(2026, 10, 24, 7, 30));
        item.setFlight(new FlightSnapshot(number, null, null, null, null, null));
        itinerary.save(slug, item);
    }

    private void givenArrivedRecord(String number) {
        Airport tll = new Airport("TLL", "EETN", "Tallinn", "Tallinn", "EE", "Europe/Tallinn", 59.4133, 24.8328);
        Airport ams = new Airport("AMS", "EHAM", "Amsterdam", "Amsterdam", "NL", "Europe/Amsterdam", 52.3086, 4.763889);
        FlightSchedule schedule = new FlightSchedule(number, "Arrived", null, "airBaltic",
                new FlightSchedule.Leg(tll, "2026-10-24T07:30+03:00", "2026-10-24T04:30Z", null, null, null),
                new FlightSchedule.Leg(ams, "2026-10-24T09:00+02:00", "2026-10-24T07:00Z", null, null, null), null);
        records.save(new FlightRecord(number, DAY, schedule, null,
                Instant.parse("2026-10-24T08:00:00Z"), null, false));
    }

    @Test
    void anUnauthenticatedReaderGetsAPublishedFlightWithNoCache() throws Exception {
        givenTrip("pub-trip", TripStatus.PUBLISHED, "BT857");
        givenArrivedRecord("BT857");

        MvcResult result = mvc.perform(get("/api/v1/public/trips/pub-trip/flights").param("number", "bt857").param("date", "2026-10-24"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(jsonPath("$.number").value("BT857"))
                .andExpect(jsonPath("$.schedule.departure.airport.iata").value("TLL"))
                .andExpect(jsonPath("$.scheduleFetchedAt").exists())
                .andExpect(jsonPath("$.ttlSeconds").isNumber())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("59.4133", "24.8328", "52.3086", "4.763889", "lat", "lon", "icao");
    }

    @Test
    void aBadDateOrAMissingParameterIsA400WithNoStackTrace() throws Exception {
        givenTrip("pub-bad", TripStatus.PUBLISHED, "BT857");
        Logger handlerLog = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        handlerLog.addAppender(logged);
        try {
            mvc.perform(get("/api/v1/public/trips/pub-bad/flights").param("number", "BT857").param("date", "garbage"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_parameter"));
            mvc.perform(get("/api/v1/public/trips/pub-bad/flights").param("date", "2026-10-24"))
                    .andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/public/trips/pub-bad/flights").param("number", "BT857"))
                    .andExpect(status().isBadRequest());
        } finally {
            handlerLog.detachAppender(logged);
        }
        assertThat(logged.list).filteredOn(e -> e.getLevel() == Level.ERROR).isEmpty();
    }

    @Test
    void aDraftTripsFlightIsANotFound() throws Exception {
        givenTrip("draft-trip", TripStatus.DRAFT, "BT857");
        givenArrivedRecord("BT857");

        mvc.perform(get("/api/v1/public/trips/draft-trip/flights").param("number", "BT857").param("date", "2026-10-24"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPublishedFlightWithNothingToShowIsNoContent() throws Exception {
        givenTrip("quiet-trip", TripStatus.PUBLISHED, "LH100");

        mvc.perform(get("/api/v1/public/trips/quiet-trip/flights").param("number", "LH100").param("date", "2026-10-24"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void aFlightNotOnThePublishedTripIsANotFound() throws Exception {
        givenTrip("other-trip", TripStatus.PUBLISHED, "BT857");

        mvc.perform(get("/api/v1/public/trips/other-trip/flights").param("number", "KL999").param("date", "2026-10-24"))
                .andExpect(status().isNotFound());
    }
}
