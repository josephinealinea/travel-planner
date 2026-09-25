package com.josephinealinea.planner.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ProxySecretFilterTest {

    private static final String SECRET = "s3cret-from-the-proxy";

    @Test
    void aBlankSecretLetsEverythingThrough() throws Exception {
        for (String blank : new String[] {null, "", "   "}) {
            var result = run(new ProxyProperties(blank), request("POST", "/api/v1/trips", null));

            assertThat(result.passed()).as("secret %s", blank).isTrue();
            assertThat(result.response().getStatus()).isEqualTo(200);
        }
    }

    @Test
    void aRequestWithoutTheHeaderIsRefused() throws Exception {
        var result = run(new ProxyProperties(SECRET), request("GET", "/api/v1/auth/me", null));

        assertThat(result.passed()).isFalse();
        assertThat(result.response().getStatus()).isEqualTo(403);
        assertThat(result.response().getContentAsString()).contains("\"code\":\"proxy_required\"");
    }

    @Test
    void aWrongSecretIsRefused() throws Exception {
        for (String wrong : new String[] {"", "nope", SECRET + "x", SECRET.substring(1)}) {
            var result = run(new ProxyProperties(SECRET), request("GET", "/api/v1/trips", wrong));

            assertThat(result.passed()).as("header %s", wrong).isFalse();
            assertThat(result.response().getStatus()).isEqualTo(403);
        }
    }

    @Test
    void theRightSecretIsLetThrough() throws Exception {
        var result = run(new ProxyProperties(SECRET), request("POST", "/api/v1/trips", SECRET));

        assertThat(result.passed()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
    }

    @Test
    void healthIsAlwaysReachableSoCloudRunCanProbeIt() throws Exception {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness"}) {
            var result = run(new ProxyProperties(SECRET), request("GET", path, null));

            assertThat(result.passed()).as(path).isTrue();
        }
    }

    @Test
    void onlyHealthIsExemptNotEverythingThatStartsLikeIt() throws Exception {
        for (String path : new String[] {"/actuator/healthz", "/actuator/env", "/p/some-trip/"}) {
            var result = run(new ProxyProperties(SECRET), request("GET", path, null));

            assertThat(result.passed()).as(path).isFalse();
        }
    }

    private static MockHttpServletRequest request(String method, String uri, String secret) {
        var request = new MockHttpServletRequest(method, uri);
        if (secret != null) request.addHeader(ProxySecretFilter.HEADER, secret);
        return request;
    }

    private record Result(boolean passed, MockHttpServletResponse response) {}

    private static Result run(ProxyProperties props, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        new ProxySecretFilter(props, TestFilterErrors.create()).doFilter(request, response, chain);
        return new Result(chain.getRequest() != null, response);
    }
}
