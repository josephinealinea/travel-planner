package com.josephinealinea.planner.flights;

import com.josephinealinea.planner.resilience.CircuitBreaker;
import com.josephinealinea.planner.resilience.CircuitBreakerProperties;
import com.josephinealinea.planner.flights.domain.FlightLive;
import com.josephinealinea.planner.usage.api.ApiUsageService;
import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import com.josephinealinea.planner.weather.CannedHttp;
import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AviationStackClientTest {

    private static final String KL2842 = """
            {"pagination":{"limit":100,"offset":0,"count":2,"total":2},"data":[
             {"flight_date":"2026-09-26","flight_status":"active",
              "departure":{"iata":"TLL","gate":"8","delay":12,
                "scheduled":"2026-09-26T07:30:00+00:00","actual":"2026-09-26T07:42:00+00:00"},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"15","delay":0,
                "scheduled":"2026-09-26T09:00:00+00:00","actual":"2026-09-26T08:57:00+00:00"},
              "airline":{"name":"KLM","iata":"KL"},
              "flight":{"number":"2842","iata":"KL2842","codeshared":{"airline_name":"airbaltic",
                "airline_iata":"bt","flight_number":"857","flight_iata":"bt857","flight_icao":"bti857"}}},
             {"flight_date":"2026-09-25","flight_status":"landed",
              "departure":{"iata":"TLL","gate":"9","delay":7,"actual":"2026-09-25T07:37:00+00:00"},
              "arrival":{"iata":"AMS","gate":"A4","baggage":"16","delay":0,"actual":"2026-09-25T08:38:00+00:00"},
              "flight":{"number":"2842","iata":"KL2842","codeshared":{"flight_iata":"bt857"}}}]}
            """;

    /** A flight that simply is not a codeshare. */
    private static final String PLAIN = """
            {"data":[{"flight_date":"2026-09-26","flight_status":"scheduled",
              "departure":{"iata":"TLL","gate":null,"delay":null},
              "arrival":{"iata":"AMS","gate":null,"baggage":null,"delay":null},
              "flight":{"number":"857","iata":"BT857","codeshared":null}}]}
            """;

    private static final String RESTRICTED = """
            {"error":{"code":"function_access_restricted","message":"Your current subscription plan does not support this API function."}}
            """;

    private final ApiUsageRepository memory = new ApiUsageRepository() {
        int used;

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            if (used >= cap) return false;
            used++;
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return used;
        }
    };

    private final CircuitBreaker breaker =
            new CircuitBreaker(CircuitBreakerProperties.defaults(), java.time.Clock.systemUTC());

    private AviationStackClient client(CannedHttp http, String key) {
        FlightProperties props = new FlightProperties(null,
                new FlightProperties.Service("http://api.aviationstack.com/v1", key, 100, 90, null),
                null, null, null, null);
        return new AviationStackClient(http.client(), props, new ApiUsageService(memory), breaker);
    }

    @Test
    void aCodeshareNamesItsOperatingFlightInUpperCase() {
        Fetch<String> result = client(new CannedHttp().ok(KL2842), "key").codeshareOf("KL2842");

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        assertThat(result.value()).isEqualTo("BT857");
    }

    /** Review focus 3: rows with no codeshare are a clean miss, not a crash or a bogus mapping. */
    @Test
    void aPlainFlightHasNoCodeshare() {
        assertThat(client(new CannedHttp().ok(PLAIN), "key").codeshareOf("BT857").status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void noRowsAtAllIsNotFound() {
        assertThat(client(new CannedHttp().ok("{\"data\":[]}"), "key").codeshareOf("ZZ9999").status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void liveExtrasComeFromTheRowForThatDateWithAirportLocalTimes() {
        Fetch<FlightLive> result = client(new CannedHttp().ok(KL2842), "key").live("KL2842", LocalDate.of(2026, 9, 26));

        assertThat(result.status()).isEqualTo(Fetch.Status.FOUND);
        FlightLive live = result.value();
        assertThat(live.status()).isEqualTo("active");
        assertThat(live.departure().gate()).isEqualTo("8");
        assertThat(live.departure().delayMinutes()).isEqualTo(12);
        // The provider labels local time as UTC; the offset is dropped, not converted.
        assertThat(live.departure().actualLocal()).isEqualTo("2026-09-26T07:42:00");
        assertThat(live.arrival().gate()).isEqualTo("A4");
        assertThat(live.arrival().baggageBelt()).isEqualTo("15");
        assertThat(live.arrival().delayMinutes()).isEqualTo(0);
    }

    @Test
    void aDateItHasNoRowForIsNotFound() {
        assertThat(client(new CannedHttp().ok(KL2842), "key").live("KL2842", LocalDate.of(2026, 10, 24)).status())
                .isEqualTo(Fetch.Status.NOT_FOUND);
    }

    @Test
    void anErrorObjectIsUnavailableNotAMiss() {
        assertThat(client(new CannedHttp().status(403, RESTRICTED), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().ok(RESTRICTED), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().status(500, "boom"), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    @Test
    void noKeyMeansNoCall() {
        CannedHttp http = new CannedHttp().ok(KL2842);

        assertThat(client(http, "").codeshareOf("KL2842").status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).isZero();
    }

    @Test
    void theKeyIsSentAsAQueryParameter() {
        CannedHttp http = new CannedHttp().ok(KL2842);

        client(http, "secret123").codeshareOf("kl 2842");

        assertThat(http.asked().get(0).toString()).contains("flight_iata=KL2842").contains("access_key=secret123");
    }

    @Test
    void aBodyThatIsNotADataArrayIsUnavailableNotAMiss() {
        assertThat(client(new CannedHttp().ok("{\"foo\":1}"), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().ok("null"), "key").codeshareOf("KL2842").status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client(new CannedHttp().ok("{\"data\":\"x\"}"), "key").live("KL2842", LocalDate.of(2026, 9, 26)).status())
                .isEqualTo(Fetch.Status.UNAVAILABLE);
    }

    /** Runs the call with the class's log captured; returns every formatted message plus throwable text. */
    private String logged(Runnable call) {
        Logger logger = (Logger) LoggerFactory.getLogger(AviationStackClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
        }
        StringBuilder all = new StringBuilder();
        for (ILoggingEvent e : appender.list) {
            all.append(e.getFormattedMessage()).append('\n');
            for (var t = e.getThrowableProxy(); t != null; t = t.getCause()) all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }

    @Test
    void aFailedCallNeverLogsTheKey() {
        ClientHttpRequestFactory failing = (uri, method) -> {
            throw new IOException("connect timed out for " + uri);
        };
        AviationStackClient c = new AviationStackClient(
                RestClient.builder().baseUrl("http://api.aviationstack.com/v1").requestFactory(failing).build(),
                new FlightProperties(null, new FlightProperties.Service("http://api.aviationstack.com/v1", "secret123", 100, 90, null),
                        null, null, null, null),
                new ApiUsageService(memory), breaker);
        Fetch<String>[] result = new Fetch[1];

        String log = logged(() -> result[0] = c.codeshareOf("kl\n2842"));

        assertThat(result[0].status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(log).isNotBlank().doesNotContain("secret123").doesNotContain("access_key=secret");
    }

    @Test
    void errorAndNon200LinesNeverLogTheKey() {
        String log = logged(() -> {
            client(new CannedHttp().status(403, "secret123"), "secret123").codeshareOf("KL2842");
            client(new CannedHttp().ok(RESTRICTED.replace("function_access_restricted", "secret123")), "secret123")
                    .codeshareOf("KL2842");
            client(new CannedHttp().ok("{}"), "secret123").codeshareOf("KL2842");
        });

        assertThat(log).isNotBlank().doesNotContain("secret123");
    }

    // ---- the circuit breaker ----

    @Test
    void threeFailedCallsOpenTheBreakerAndThenNothingIsCalledOrSpent() {
        CannedHttp http = new CannedHttp().status(500, "a").ok(RESTRICTED).ok("{}").ok(PLAIN);
        AviationStackClient client = client(http, "key");

        for (int i = 0; i < 3; i++) client.codeshareOf("KL2842");
        assertThat(breaker.isOpen(AviationStackClient.SERVICE)).isTrue();

        assertThat(client.codeshareOf("KL2842").status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(client.live("BT857", LocalDate.of(2026, 9, 26)).status()).isEqualTo(Fetch.Status.UNAVAILABLE);
        assertThat(http.callCount()).as("no outbound call while open").isEqualTo(3);
        assertThat(memory.calls(AviationStackClient.SERVICE, "m")).as("no quota spent while open").isEqualTo(3);
    }

    @Test
    void anEmptyDataArrayIsAnAnswerAndResetsTheStreak() {
        CannedHttp http = new CannedHttp().status(500, "a").status(500, "b").ok("{\"data\":[]}")
                .status(500, "c").status(500, "d");
        AviationStackClient client = client(http, "key");

        for (int i = 0; i < 5; i++) client.codeshareOf("KL2842");

        assertThat(breaker.isOpen(AviationStackClient.SERVICE)).isFalse();
        assertThat(http.callCount()).isEqualTo(5);
    }

    @Test
    void noKeyIsNeverReportedAsAFailure() {
        CircuitBreaker spy = org.mockito.Mockito.spy(breaker);
        CannedHttp http = new CannedHttp();
        FlightProperties keyless = new FlightProperties(null,
                new FlightProperties.Service("http://api.aviationstack.com/v1", "", 100, 90, null),
                null, null, null, null);

        for (int i = 0; i < 5; i++) new AviationStackClient(http.client(), keyless, new ApiUsageService(memory), spy).codeshareOf("KL2842");

        org.mockito.Mockito.verify(spy, org.mockito.Mockito.never()).failure(AviationStackClient.SERVICE);
        assertThat(http.callCount()).isZero();
    }
}
