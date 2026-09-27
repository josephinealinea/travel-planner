package com.josephinealinea.planner.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One circuit breaker for every outside service, so a provider's outage stops
 * costing calls (and quota) instead of being retried by every request.
 *
 * <b>Service-wide.</b> {@link #isOpen(String)} / {@link #success(String)} /
 * {@link #failure(String)} per named service ({@code "aerodatabox"},
 * {@code "aviationstack"}, ...). After {@code failure-threshold} consecutive
 * failed calls the service is open for the first rung of {@code back-off}; once
 * that lapses exactly one trial call is let through: the first {@code isOpen}
 * after the pause claims it atomically and every other caller still sees the
 * service open until the trial reports (a failure re-opens it on the next rung,
 * the last rung repeating) or {@link #TRIAL_TIMEOUT} passes with no report, when
 * another trial may go. A success closes it and resets both the streak and the
 * ladder. {@link #paused(String)} asks the same question without claiming.
 *
 * <b>Per key.</b> The same three calls with a key ({@code "BT857|2026-10-24"})
 * back off one thing rather than a whole service: every failure pauses that key
 * for the next rung of the service's ladder, so the first failure waits
 * {@code back-off[0]}, the second {@code back-off[1]}, and so on. That is a
 * breaker with a threshold of one, which is exactly how it is kept.
 *
 * <b>What a failure is.</b> A call that was actually made and produced no real
 * answer: a timeout, a connection error, a 429 or 5xx, a 200 with an unreadable
 * or error body. "No such thing" is an answer and is reported as a success. A
 * call that was never made (no key, a monthly cap reached, the breaker itself
 * open) is reported as nothing at all. Failures reported while already open
 * (calls that were in flight) change nothing.
 *
 * In memory, like the other guards: YAML mode and Cloud Run are single-instance.
 * Services are a handful of constants; keys are trimmed once there are more than
 * {@value #MAX_KEYS} of them.
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);
    static final int MAX_KEYS = 1000;
    static final Duration TRIAL_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Consecutive failures, how many times it has opened since the last success,
     * until when, the last failure, and whether a trial call is out (then
     * {@code openUntil} is when that trial is given up on).
     */
    private record State(int streak, int opens, Instant openUntil, Instant lastFailure, boolean trial) {

        boolean openAt(Instant now) {
            return openUntil != null && now.isBefore(openUntil);
        }
    }

    private final CircuitBreakerProperties props;
    private final Clock clock;
    private final Map<String, State> services = new ConcurrentHashMap<>();
    private final Map<String, State> keys = new ConcurrentHashMap<>();

    @Autowired // two constructors: Spring must be told which one is real
    public CircuitBreaker(CircuitBreakerProperties props) {
        this(props, Clock.systemUTC());
    }

    public CircuitBreaker(CircuitBreakerProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    // ---- service-wide ----

    /** True while the service is paused: skip the call and answer "unavailable". */
    public boolean isOpen(String service) {
        Instant now = clock.instant();
        boolean[] claimed = {false};
        services.computeIfPresent(service, (name, state) -> {
            if (state.openUntil() == null || state.openAt(now)) return state; // closed, or still paused
            // The pause is over and no trial is out: this caller makes it. Everyone
            // else sees it open until the trial reports, or TRIAL_TIMEOUT passes.
            claimed[0] = true;
            return new State(state.streak(), state.opens(), now.plus(TRIAL_TIMEOUT), state.lastFailure(), true);
        });
        if (claimed[0]) return false;
        State state = services.get(service);
        return state != null && state.openAt(now);
    }

    /**
     * Whether a call to this service would be skipped right now, without claiming
     * the trial call the way {@link #isOpen(String)} does. For a caller that only
     * needs to know (to avoid spending something of its own on a call the
     * client will skip), never for the client deciding to call.
     */
    public boolean paused(String service) {
        State state = services.get(service);
        return state != null && state.openAt(clock.instant());
    }

    public void success(String service) {
        State was = services.remove(service);
        if (was != null && was.opens() > 0) log.info("{} answered again; calls resume", service);
    }

    public void failure(String service) {
        CircuitBreakerProperties.Settings settings = props.settingsFor(service);
        Instant now = clock.instant();
        boolean[] opened = {false};
        State after = services.compute(service, (name, was) -> {
            State next = next(was, settings.failureThreshold(), settings, now);
            opened[0] = next != was && next.openAt(now) && !next.trial();
            return next;
        });
        // Once per opening, never per skipped call; the service name only, never a key or URL.
        if (opened[0]) {
            log.info("{} failed {} times in a row; pausing calls for {}", service, after.streak(),
                    settings.rung(after.opens()));
        }
    }

    // ---- per key ----

    /**
     * True while this key is backing off after a failure. No trial is claimed:
     * a key is one thing, asked about by one caller at a time under its own lock.
     */
    public boolean isOpen(String service, String key) {
        State state = keys.get(service + "|" + key);
        return state != null && state.openAt(clock.instant());
    }

    public void success(String service, String key) {
        keys.remove(service + "|" + key);
    }

    public void failure(String service, String key) {
        CircuitBreakerProperties.Settings settings = props.settingsFor(service);
        Instant now = clock.instant();
        keys.compute(service + "|" + key, (k, was) -> next(was, 1, settings, now));
        if (keys.size() > MAX_KEYS) {
            Duration longest = settings.longest();
            keys.values().removeIf(s -> !s.openAt(now) && Duration.between(s.lastFailure(), now).compareTo(longest) >= 0);
        }
    }

    int trackedKeys() {
        return keys.size();
    }

    // ---- the one rule ----

    private static State next(State was, int threshold, CircuitBreakerProperties.Settings settings, Instant now) {
        // Already paused: in-flight failures change nothing. A trial's failure does:
        // it re-opens on the next rung.
        if (was != null && was.openAt(now) && !was.trial()) return was;
        int streak = was == null ? 1 : was.streak() + 1;
        int opens = was == null ? 0 : was.opens();
        if (streak < threshold) return new State(streak, opens, was == null ? null : was.openUntil(), now, false);
        return new State(streak, opens + 1, now.plus(settings.rung(opens + 1)), now, false);
    }
}
