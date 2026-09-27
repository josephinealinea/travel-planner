package com.josephinealinea.planner.flights;

/**
 * What asking an outside service came to. Three outcomes, never conflated:
 * {@code NOT_FOUND} is the service answering that it has no such flight (worth
 * remembering briefly); {@code UNAVAILABLE} is everything else that stopped an
 * answer (no key, cap reached, timeout, error) and must never be remembered as
 * a miss, or an outage would poison the cache.
 */
public record Fetch<T>(Status status, T value) {

    public enum Status { FOUND, NOT_FOUND, UNAVAILABLE }

    public static <T> Fetch<T> found(T value) {
        return new Fetch<>(Status.FOUND, value);
    }

    public static <T> Fetch<T> notFound() {
        return new Fetch<>(Status.NOT_FOUND, null);
    }

    public static <T> Fetch<T> unavailable() {
        return new Fetch<>(Status.UNAVAILABLE, null);
    }

    /** Carries a non-found outcome across a change of value type. */
    public static <T> Fetch<T> of(Status status) {
        return new Fetch<>(status, null);
    }
}
