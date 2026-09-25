package com.josephinealinea.planner.shared;

import org.springframework.http.HttpStatus;

/**
 * A deliberate, client-facing failure. Rendered as RFC-7807 ProblemDetail.
 *
 * It carries a message <em>key</em> and its arguments, never the words: the
 * handler looks them up in the language of the request, so the same failure
 * can be thrown from static code (a slug check, a date window) and still reach
 * the reader in their own language. The words live in
 * {@code messages_<language>.properties}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Object[] args;

    public ApiException(HttpStatus status, String code, String messageKey, Object... args) {
        // The key, so a stack trace or log line is greppable in the message file.
        super(messageKey);
        this.status = status;
        this.code = code;
        this.args = args;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String messageKey() { return getMessage(); }
    public Object[] args() { return args; }

    /** {@code messageKey} names the whole sentence, e.g. {@code error.trip.notFound}. */
    public static ApiException notFound(String messageKey, Object... args) {
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", messageKey, args);
    }

    public static ApiException badRequest(String messageKey, Object... args) {
        return new ApiException(HttpStatus.BAD_REQUEST, "bad_request", messageKey, args);
    }

    public static ApiException conflict(String code, String messageKey, Object... args) {
        return new ApiException(HttpStatus.CONFLICT, code, messageKey, args);
    }

    public static ApiException forbidden(String messageKey, Object... args) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", messageKey, args);
    }

    public static ApiException unauthorized(String messageKey, Object... args) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", messageKey, args);
    }
}
