package com.josephinealinea.planner.flights.web;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.config.CurrentUserContext;
import com.josephinealinea.planner.flights.AeroDataBoxClient;
import com.josephinealinea.planner.flights.AviationStackClient;
import com.josephinealinea.planner.flights.FlightProperties;
import com.josephinealinea.planner.flights.api.FlightData;
import com.josephinealinea.planner.flights.api.FlightFreshness;
import com.josephinealinea.planner.flights.api.FlightLookup;
import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import com.josephinealinea.planner.flights.domain.FlightRecord;
import com.josephinealinea.planner.flights.infra.CodeshareRepository;
import com.josephinealinea.planner.flights.infra.FlightRecordRepository;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.identity.api.CurrentUser;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.shared.GlobalExceptionHandler;
import com.josephinealinea.planner.trips.api.TripAccessService;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FlightControllerTest {

    /** Departs 23:30 Tallinn time on the 24th, lands 01:10 Amsterdam time on the 25th. */
    private static final String OVERNIGHT = """
            [{"departure":{"airport":{"icao":"EETN","iata":"TLL","name":"Tallinn","municipalityName":"Tallinn",
                  "location":{"lat":59.4,"lon":24.8},"countryCode":"EE","timeZone":"Europe/Tallinn"},
                "scheduledTime":{"utc":"2026-10-24 20:30Z","local":"2026-10-24 23:30+03:00"}},
              "arrival":{"airport":{"icao":"EHAM","iata":"AMS","name":"Amsterdam","municipalityName":"Amsterdam",
                  "location":{"lat":52.3,"lon":4.7},"countryCode":"NL","timeZone":"Europe/Amsterdam"},
                "scheduledTime":{"utc":"2026-10-24 23:10Z","local":"2026-10-25 01:10+02:00"}},
              "lastUpdatedUtc":"2026-06-02 08:13Z","number":"BT 857","status":"Expected"}]
            """;

    private final List<FlightRecord> rows = new ArrayList<>();
    private final FlightRecordRepository records = new FlightRecordRepository() {
        public Optional<FlightRecord> find(String n, LocalDate d) {
            return rows.stream().filter(r -> r.flightNumber().equals(n) && r.departureDate().equals(d)).findFirst();
        }
        public void save(FlightRecord r) { rows.add(r); }
        public List<FlightRecord> findAll() { return List.copyOf(rows); }
    };
    private final CodeshareRepository codeshares = new CodeshareRepository() {
        public Optional<String> operatingFor(String b) { return Optional.empty(); }
        public void save(CodeshareMapping m) {}
        public List<CodeshareMapping> findAll() { return List.of(); }
    };
    private final ApiUsageRepository usage = new ApiUsageRepository() {
        public boolean tryAcquire(String s, String m, int cap) { return true; }
        public int calls(String s, String m) { return 0; }
    };

    private final TripAccessService access = Mockito.mock(TripAccessService.class);

    private MockMvc mvc(CannedHttp aero, CannedHttp stack) {
        FlightProperties props = new FlightProperties(
                new FlightProperties.Service("https://aerodatabox.p.rapidapi.com", "key", 400, 90, null),
                new FlightProperties.Service("http://api.aviationstack.com/v1", "key", 100, 90, null),
                null, null, null, null);
        CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerProperties.defaults(), java.time.Clock.systemUTC());
        FlightData data = new FlightData(
                new AeroDataBoxClient(aero.client(), props, new ApiUsageService(usage), breaker),
                records, new FlightFreshness(props));
        FlightLookup lookup = new FlightLookup(data,
                new AviationStackClient(stack.client(), props, new ApiUsageService(usage), breaker), codeshares);
        CurrentUserContext user = new CurrentUserContext();
        user.set(new CurrentUser("u1", "a@b.c", "A", false, null));
        return MockMvcBuilders.standaloneSetup(new FlightController(lookup, access, user))
                .setControllerAdvice(new GlobalExceptionHandler(I18nConfig.standalone()))
                .build();
    }

    @Test
    void anOvernightFlightFillsTheArrivalsOwnDateAndBothWallClockTimes() throws Exception {
        mvc(new CannedHttp().ok(OVERNIGHT), new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "bt857").param("date", "2026-10-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("found"))
                .andExpect(jsonPath("$.departureTime").value("23:30"))
                .andExpect(jsonPath("$.arrivalDate").value("2026-10-25"))
                .andExpect(jsonPath("$.arrivalTime").value("01:10"))
                .andExpect(jsonPath("$.flight.number").value("BT857"))
                .andExpect(jsonPath("$.flight.from.iata").value("TLL"));
    }

    @Test
    void aMissIsNotFound() throws Exception {
        mvc(new CannedHttp().ok(""), new CannedHttp().ok("{\"data\":[]}"))
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "KL2842").param("date", "2026-10-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("notFound"))
                .andExpect(jsonPath("$.flight").doesNotExist());
    }

    @Test
    void anOutageIsUnavailable() throws Exception {
        mvc(new CannedHttp().status(500, "boom"), new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "BT857").param("date", "2026-10-24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }

    @Test
    void aNonMemberGetsNotFound() throws Exception {
        Mockito.when(access.requireMember(eq("t1"), any())).thenThrow(ApiException.notFound("error.trip.notFound"));
        CannedHttp aero = new CannedHttp();

        mvc(aero, new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "BT857").param("date", "2026-10-24"))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(aero.callCount()).isZero();
    }

    @Test
    void aMalformedNumberIsABadRequest() throws Exception {
        mvc(new CannedHttp(), new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "nonsense").param("date", "2026-10-24"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNumberWithSeveralLegsThatDayOffersEachAsAChoiceAndPicksNone() throws Exception {
        mvc(new CannedHttp().ok(com.josephinealinea.planner.flights.MultiLegFixtures.AV105), new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "AV105").param("date", "2026-10-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("found"))
                .andExpect(jsonPath("$.flight").doesNotExist())
                .andExpect(jsonPath("$.departureTime").doesNotExist())
                .andExpect(jsonPath("$.choices.length()").value(2))
                .andExpect(jsonPath("$.choices[0].flight.from.iata").value("BOG"))
                .andExpect(jsonPath("$.choices[1].flight.from.iata").value("CUZ"))
                .andExpect(jsonPath("$.choices[1].departureTime").value("12:30"))
                .andExpect(jsonPath("$.choices[1].arrivalTime").value("14:45"));
    }

    @Test
    void aSingleLegHasNoChoices() throws Exception {
        mvc(new CannedHttp().ok(OVERNIGHT), new CannedHttp())
                .perform(get("/api/v1/trips/t1/flights/lookup").param("number", "bt857").param("date", "2026-10-24"))
                .andExpect(jsonPath("$.choices.length()").value(0));
    }
}
