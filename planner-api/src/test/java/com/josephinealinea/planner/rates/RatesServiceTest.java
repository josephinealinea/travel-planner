package com.josephinealinea.planner.rates;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.rates.api.RatesService;
import com.josephinealinea.planner.rates.domain.RateTable;
import com.josephinealinea.planner.rates.infra.RatesRepository;
import com.josephinealinea.planner.rates.infra.YamlRatesRepository;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fetching and holding the install's exchange rates, without the provider.
 *
 * The body below is a real response from open.er-api.com, trimmed. Two
 * properties matter more than the parsing:
 *
 * <ul>
 *   <li><b>only the catalogue is kept.</b> The provider sends 166 currencies;
 *       storing all of them would bury the file and list codes nobody can
 *       select;</li>
 *   <li><b>a failed fetch never clears what is held.</b> Rates a day old are a
 *       rounding difference. No rates at all is every budget total on the
 *       install reading as unconvertible.</li>
 * </ul>
 */
class RatesServiceTest {

    /** Real shape, including the currencies an ECB feed would not carry. */
    private static final String LIVE_BODY = """
            {"result":"success",
             "provider":"https://www.exchangerate-api.com",
             "time_last_update_utc":"Mon, 14 Sep 2026 00:02:31 +0000",
             "base_code":"EUR",
             "rates":{"EUR":1,"USD":1.159763,"PEN":3.891723,"BOB":14.432679,
                      "SGD":1.48,"XPF":119.33,"ZWL":37.4}}
            """;

    private static AppProperties props(Path dir) {
        return new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(null, null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                // An explicit catalogue, so the filtering is actually under
                // test: PEN and BOB are in it, XPF and ZWL are not. (The
                // hardcoded fallback in AppProperties is majors-only; the real
                // catalogue lives in application.yml.)
                new AppProperties.Currencies(null, null,
                        List.of("EUR", "USD", "PEN", "BOB", "SGD")));
    }

    private static RatesService service(Path dir, Stub stub) {
        AppProperties props = props(dir);
        return new RatesService(
                new ExchangeRatesClient(RestClient.builder().requestFactory(stub).build(), props),
                new YamlRatesRepository(new YamlStore(), new YamlPaths(props)));
    }

    @Test
    void aFetchKeepsOnlyTheCurrenciesInTheCatalogue(@TempDir Path dir) {
        RatesService rates = service(dir, new Stub().ok(LIVE_BODY));

        assertThat(rates.refresh()).isTrue();
        RateTable table = rates.current();

        assertThat(table.getBase()).isEqualTo("EUR");
        assertThat(table.rateFor("PEN")).isEqualByComparingTo("3.891723");
        assertThat(table.rateFor("USD")).isEqualByComparingTo("1.159763");
        // XPF and ZWL are real codes the provider sends and the catalogue omits.
        assertThat(table.rateFor("BOB")).isEqualByComparingTo("14.432679");
        // XPF and ZWL are real codes the provider sends and the catalogue omits.
        assertThat(table.getRates()).doesNotContainKeys("XPF", "ZWL");
    }

    /** The base is stored as 1 so no currency is a special case downstream. */
    @Test
    void theBaseIsStoredAsOne(@TempDir Path dir) {
        RatesService rates = service(dir, new Stub().ok(LIVE_BODY));
        rates.refresh();

        assertThat(rates.current().rateFor("EUR")).isEqualByComparingTo("1");
    }

    @Test
    void ratesSurviveARestartByWayOfTheirFile(@TempDir Path dir) throws Exception {
        service(dir, new Stub().ok(LIVE_BODY)).refresh();

        assertThat(Files.readString(dir.resolve("rates.yml")))
                .contains("base: EUR")
                .contains("PEN:");

        // A fresh service, and a provider that would fail if asked.
        RatesService afterRestart = service(dir, new Stub());
        assertThat(afterRestart.current().rateFor("PEN")).isEqualByComparingTo("3.891723");
    }

    @Test
    void aFailedFetchKeepsThePreviousTable(@TempDir Path dir) {
        Stub stub = new Stub().ok(LIVE_BODY).status(503, "nope");
        RatesService rates = service(dir, stub);

        assertThat(rates.refresh()).isTrue();
        assertThat(rates.refresh()).isFalse();

        assertThat(rates.current().rateFor("PEN")).isEqualByComparingTo("3.891723");
    }

    /** The provider answers 200 with result:"error" for a bad base. */
    @Test
    void anErrorResultIsTreatedAsAFailureDespiteTheTwoHundred(@TempDir Path dir) {
        RatesService rates = service(dir,
                new Stub().ok("{\"result\":\"error\",\"error-type\":\"unsupported-code\"}"));

        assertThat(rates.refresh()).isFalse();
        assertThat(rates.current().isEmpty()).isTrue();
    }

    @Test
    void nothingFetchedYetIsAnEmptyTableRatherThanNull(@TempDir Path dir) {
        assertThat(service(dir, new Stub()).current())
                .isNotNull()
                .satisfies(table -> assertThat(table.isEmpty()).isTrue());
    }

    @Test
    void theDefaultCronIsDailyAndTheBaseIsEuro(@TempDir Path dir) {
        var config = new AppProperties.Rates(null, null, null, null);
        assertThat(config.base()).isEqualTo("EUR");
        assertThat(config.cron()).isEqualTo("0 30 1 * * *");
        assertThat(config.baseUrl()).isEqualTo("https://open.er-api.com");
    }

    // ── a RestClient that answers from a script ─────

    private static final class Stub implements ClientHttpRequestFactory {
        private final List<Object[]> script = new ArrayList<>();

        Stub ok(String body) { script.add(new Object[]{HttpStatus.OK, body}); return this; }
        Stub status(int code, String body) {
            script.add(new Object[]{HttpStatusCode.valueOf(code), body});
            return this;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            Object[] canned = script.isEmpty()
                    ? new Object[]{HttpStatus.INTERNAL_SERVER_ERROR, "unscripted"}
                    : script.remove(0);
            return new ClientHttpRequest() {
                @Override public ClientHttpResponse execute() {
                    return new ClientHttpResponse() {
                        @Override public HttpStatusCode getStatusCode() { return (HttpStatusCode) canned[0]; }
                        @Override public String getStatusText() { return canned[0].toString(); }
                        @Override public void close() {}
                        @Override public InputStream getBody() {
                            return new ByteArrayInputStream(((String) canned[1]).getBytes());
                        }
                        @Override public HttpHeaders getHeaders() {
                            HttpHeaders headers = new HttpHeaders();
                            headers.setContentType(MediaType.APPLICATION_JSON);
                            return headers;
                        }
                    };
                }
                @Override public OutputStream getBody() { return OutputStream.nullOutputStream(); }
                @Override public HttpMethod getMethod() { return method; }
                @Override public URI getURI() { return uri; }
                @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
                @Override public java.util.Map<String, Object> getAttributes() {
                    return new java.util.HashMap<>();
                }
            };
        }
    }
}
