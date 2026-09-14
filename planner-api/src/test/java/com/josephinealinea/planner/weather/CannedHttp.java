package com.josephinealinea.planner.weather;

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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * A RestClient that answers from a script instead of the network.
 *
 * Open-Meteo is free, keyless and rate-limited on bursts, so a test suite must
 * never actually call it: the calls would be slow, would fail in CI with no
 * network, and — worst — would fail *intermittently* on a developer machine
 * depending on how recently anything else had asked. The responses canned in
 * these tests are real ones, captured from the live service.
 *
 * Each queued entry answers one request, in order, and every request's URI is
 * recorded so a test can assert how many calls were made and what they asked
 * for — which is the point of the bulk-coordinate design.
 */
final class CannedHttp implements ClientHttpRequestFactory {

    private final List<Canned> script = new ArrayList<>();
    private final List<URI> asked = new ArrayList<>();

    private record Canned(HttpStatusCode status, String body) {}

    CannedHttp ok(String body) {
        script.add(new Canned(HttpStatus.OK, body));
        return this;
    }

    CannedHttp status(int code, String body) {
        script.add(new Canned(HttpStatusCode.valueOf(code), body));
        return this;
    }

    List<URI> asked() {
        return List.copyOf(asked);
    }

    int callCount() {
        return asked.size();
    }

    RestClient client() {
        return RestClient.builder().requestFactory(this).build();
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        asked.add(uri);
        Canned canned = script.isEmpty()
                // An unscripted call is a test bug, not a scenario. Answering
                // 500 rather than throwing keeps the failure readable: the
                // assertion on callCount fails, instead of a stack trace from
                // inside the client.
                ? new Canned(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":true,\"reason\":\"unscripted\"}")
                : script.remove(0);
        return new StubRequest(uri, method, canned);
    }

    private record StubRequest(URI uri, HttpMethod method, Canned canned) implements ClientHttpRequest {

        @Override
        public ClientHttpResponse execute() {
            return new StubResponse(canned);
        }

        @Override
        public OutputStream getBody() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return new java.util.HashMap<>();
        }
    }

    private record StubResponse(Canned canned) implements ClientHttpResponse {

        @Override
        public HttpStatusCode getStatusCode() {
            return canned.status();
        }

        @Override
        public String getStatusText() {
            return canned.status().toString();
        }

        @Override
        public void close() {}

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(canned.body().getBytes());
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }
    }

    /** Convenience for the "never called" client in a test that only uses one. */
    static RestClient unused() {
        return new CannedHttp().client();
    }

    static IOException unreachable() {
        return new IOException("unreachable");
    }
}
