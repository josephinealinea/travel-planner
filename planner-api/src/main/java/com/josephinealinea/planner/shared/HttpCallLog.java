package com.josephinealinea.planner.shared;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logs every call to an outside service at INFO: the request line, then the
 * status, time taken and (truncated) body. One place, applied to every
 * {@code RestClient} that leaves the app, so a bad answer from somebody else's
 * server can be read in the log rather than guessed at.
 *
 * <p>Headers are never logged (RapidAPI's key travels in one) and secret-looking
 * query parameters are masked (AviationStack's key is one). Bodies are cut at
 * {@link #MAX_BODY} characters; the full length is still shown.
 */
public final class HttpCallLog {

    private static final Logger log = LoggerFactory.getLogger("external-api");
    private static final int MAX_BODY = 2000;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)([?&](?:access_key|api_key|apikey|key|token)=)[^&\\s]*");

    private HttpCallLog() {}

    /** Adds the logging to a builder; {@code service} is the name shown in each line. */
    public static RestClient.Builder on(RestClient.Builder builder, String service) {
        return builder.requestInterceptor(interceptor(service));
    }

    public static String redact(String text) {
        return text == null ? null : SECRET.matcher(text).replaceAll("$1***");
    }

    static ClientHttpRequestInterceptor interceptor(String service) {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution next) -> {
            String target = redact(request.getURI().toString());
            log.info("{} request: {} {}", service, request.getMethod(), target);
            long started = System.nanoTime();
            ClientHttpResponse response;
            try {
                response = next.execute(request, body);
            } catch (IOException e) {
                log.info("{} response: {} {} failed after {} ms: {}", service, request.getMethod(), target,
                        (System.nanoTime() - started) / 1_000_000, redact(e.toString()));
                throw e;
            }
            byte[] bytes = response.getBody().readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            String shown = text.length() > MAX_BODY ? text.substring(0, MAX_BODY) + "…" : text;
            log.info("{} response: {} {} in {} ms ({} chars): {}", service, response.getStatusCode().value(),
                    target, (System.nanoTime() - started) / 1_000_000, text.length(), redact(shown));
            return new Replayed(response, bytes);
        };
    }

    /** The response with its body read once already: the caller gets the same bytes again. */
    private record Replayed(ClientHttpResponse inner, byte[] body) implements ClientHttpResponse {
        @Override public HttpStatusCode getStatusCode() throws IOException { return inner.getStatusCode(); }
        @Override public String getStatusText() throws IOException { return inner.getStatusText(); }
        @Override public HttpHeaders getHeaders() { return inner.getHeaders(); }
        @Override public InputStream getBody() { return new ByteArrayInputStream(body); }
        @Override public void close() { inner.close(); }
    }
}
