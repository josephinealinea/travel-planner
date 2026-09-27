package com.josephinealinea.planner.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        void advanceMinutes(long m) { now = now.plusSeconds(m * 60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final CircuitBreaker breaker = new CircuitBreaker(CircuitBreakerProperties.defaults(), clock);

    private void fail(int times) {
        for (int i = 0; i < times; i++) breaker.failure("svc");
    }

    // ---- configuration ----

    @Test
    void defaultsAreThreeFailuresAndFifteenMinutesAnHourSixHours() {
        CircuitBreakerProperties props = CircuitBreakerProperties.defaults();

        assertThat(props.failureThreshold()).isEqualTo(3);
        assertThat(props.backOff()).containsExactly(Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));
        assertThat(props.settingsFor("anything").failureThreshold()).isEqualTo(3);
    }

    @Test
    void aPerServiceOverrideWinsOverTheGlobalValue() {
        CircuitBreakerProperties props = new CircuitBreakerProperties(3, null,
                Map.of("aviationstack", new CircuitBreakerProperties.Service(2, List.of(Duration.ofMinutes(1)))));

        assertThat(props.settingsFor("aviationstack").failureThreshold()).isEqualTo(2);
        assertThat(props.settingsFor("aviationstack").backOff()).containsExactly(Duration.ofMinutes(1));
        assertThat(props.settingsFor("aerodatabox").failureThreshold()).isEqualTo(3);
        assertThat(props.settingsFor("aerodatabox").backOff()).hasSize(3);
    }

    @Test
    void anOverrideThatOmitsAFieldInheritsTheGlobalOne() {
        CircuitBreakerProperties props = new CircuitBreakerProperties(4, List.of(Duration.ofMinutes(5)),
                Map.of("aviationstack", new CircuitBreakerProperties.Service(null, null)));

        assertThat(props.settingsFor("aviationstack").failureThreshold()).isEqualTo(4);
        assertThat(props.settingsFor("aviationstack").backOff()).containsExactly(Duration.ofMinutes(5));
    }

    @Test
    void anOverriddenThresholdOpensTheBreakerSooner() {
        CircuitBreaker b = new CircuitBreaker(new CircuitBreakerProperties(null, null,
                Map.of("aviationstack", new CircuitBreakerProperties.Service(2, null))), clock);

        b.failure("aviationstack");
        b.failure("aviationstack");
        b.failure("aerodatabox");
        b.failure("aerodatabox");

        assertThat(b.isOpen("aviationstack")).isTrue();
        assertThat(b.isOpen("aerodatabox")).as("still on the global three").isFalse();
    }

    // ---- the service-wide breaker ----

    @Test
    void itOpensAfterTheThresholdOfConsecutiveFailuresAndNotBefore() {
        fail(2);
        assertThat(breaker.isOpen("svc")).isFalse();

        fail(1);
        assertThat(breaker.isOpen("svc")).isTrue();
        assertThat(breaker.isOpen("other")).as("one service's outage is not another's").isFalse();
    }

    @Test
    void aSuccessInBetweenResetsTheStreak() {
        fail(2);
        breaker.success("svc");
        fail(2);

        assertThat(breaker.isOpen("svc")).isFalse();
    }

    @Test
    void thePauseEscalatesFifteenMinutesThenAnHourThenSixHoursAndStaysThere() {
        fail(3);
        clock.advanceMinutes(14);
        assertThat(breaker.isOpen("svc")).as("within 15 minutes").isTrue();
        clock.advanceMinutes(1);
        assertThat(breaker.isOpen("svc")).as("after 15 minutes a trial call is allowed").isFalse();

        breaker.failure("svc"); // the trial fails: re-open, next rung
        clock.advanceMinutes(59);
        assertThat(breaker.isOpen("svc")).as("within the hour").isTrue();
        clock.advanceMinutes(1);
        assertThat(breaker.isOpen("svc")).isFalse();

        breaker.failure("svc");
        clock.advanceMinutes(6 * 60 - 1);
        assertThat(breaker.isOpen("svc")).as("within six hours").isTrue();
        clock.advanceMinutes(1);
        assertThat(breaker.isOpen("svc")).isFalse();

        breaker.failure("svc");
        clock.advanceMinutes(6 * 60 - 1);
        assertThat(breaker.isOpen("svc")).as("the last rung repeats").isTrue();
        clock.advanceMinutes(1);
        assertThat(breaker.isOpen("svc")).isFalse();
    }

    @Test
    void aSuccessClosesItAndResetsTheLadder() {
        fail(3);
        clock.advanceMinutes(15);
        breaker.failure("svc"); // second opening: an hour
        clock.advanceMinutes(60);
        breaker.success("svc");
        assertThat(breaker.isOpen("svc")).isFalse();

        fail(2);
        assertThat(breaker.isOpen("svc")).as("the streak starts again from zero").isFalse();
        fail(1);
        clock.advanceMinutes(15);
        assertThat(breaker.isOpen("svc")).as("back on the first rung, 15 minutes").isFalse();
    }

    @Test
    void failuresReportedWhileOpenDoNotAdvanceTheLadder() {
        fail(3);
        fail(5); // calls that were already in flight when it opened
        clock.advanceMinutes(15);

        assertThat(breaker.isOpen("svc")).isFalse();
    }

    // ---- the per-key back-off ----

    @Test
    void aKeyBacksOffFifteenMinutesThenAnHourThenSixHoursFromItsFirstFailure() {
        breaker.failure("svc", "BT857|2026-10-24");
        assertThat(breaker.isOpen("svc", "BT857|2026-10-24")).isTrue();
        assertThat(breaker.isOpen("svc", "LH100|2026-10-24")).as("keys are independent").isFalse();
        assertThat(breaker.isOpen("svc")).as("a key is not the service").isFalse();

        clock.advanceMinutes(15);
        assertThat(breaker.isOpen("svc", "BT857|2026-10-24")).isFalse();
        breaker.failure("svc", "BT857|2026-10-24");
        clock.advanceMinutes(59);
        assertThat(breaker.isOpen("svc", "BT857|2026-10-24")).isTrue();
        clock.advanceMinutes(1);
        breaker.failure("svc", "BT857|2026-10-24");
        clock.advanceMinutes(6 * 60 - 1);
        assertThat(breaker.isOpen("svc", "BT857|2026-10-24")).isTrue();
        clock.advanceMinutes(1);
        breaker.failure("svc", "BT857|2026-10-24");
        clock.advanceMinutes(6 * 60 - 1);
        assertThat(breaker.isOpen("svc", "BT857|2026-10-24")).as("the last rung repeats").isTrue();
    }

    @Test
    void aKeysSuccessResetsItsBackOff() {
        breaker.failure("svc", "k");
        clock.advanceMinutes(15);
        breaker.success("svc", "k");
        breaker.failure("svc", "k");
        clock.advanceMinutes(15);

        assertThat(breaker.isOpen("svc", "k")).as("15 minutes again, not the hour").isFalse();
    }

    @Test
    void perKeyBackOffUsesTheServicesConfiguredLadder() {
        CircuitBreaker b = new CircuitBreaker(new CircuitBreakerProperties(null, null,
                Map.of("svc", new CircuitBreakerProperties.Service(null, List.of(Duration.ofMinutes(2))))), clock);

        b.failure("svc", "k");
        clock.advanceMinutes(2);

        assertThat(b.isOpen("svc", "k")).isFalse();
    }

    @Test
    void keysAreTrimmedSoMemoryStaysBounded() {
        for (int i = 0; i < 5000; i++) {
            breaker.failure("svc", "k" + i);
            if (i % 100 == 0) clock.advanceMinutes(7 * 60);
        }

        assertThat(breaker.trackedKeys()).isLessThanOrEqualTo(1001);
    }

    // ---- one trial at a time (review item 3) ----

    @Test
    void whenThePauseEndsExactlyOneOfSixteenConcurrentCallersGetsTheTrial() throws Exception {
        fail(3);
        clock.advanceMinutes(15);
        int n = 16;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        List<java.util.concurrent.Future<Boolean>> answers = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            answers.add(pool.submit(() -> {
                start.await();
                return breaker.isOpen("svc");
            }));
        }
        start.countDown();
        int trials = 0;
        for (var a : answers) if (!a.get(10, java.util.concurrent.TimeUnit.SECONDS)) trials++;
        pool.shutdown();

        assertThat(trials).as("one trial call, not sixteen").isEqualTo(1);
    }

    @Test
    void whileTheTrialIsOutstandingEveryoneElseStillSeesItOpen() {
        fail(3);
        clock.advanceMinutes(15);

        assertThat(breaker.isOpen("svc")).as("the first caller makes the trial").isFalse();
        assertThat(breaker.isOpen("svc")).as("the second waits for its answer").isTrue();

        breaker.success("svc");
        assertThat(breaker.isOpen("svc")).as("the trial answered: closed for everyone").isFalse();
        assertThat(breaker.isOpen("svc")).isFalse();
    }

    @Test
    void aTrialThatFailsReopensOnTheNextRung() {
        fail(3);
        clock.advanceMinutes(15);
        assertThat(breaker.isOpen("svc")).isFalse(); // claims the trial
        breaker.failure("svc");

        clock.advanceMinutes(59);
        assertThat(breaker.isOpen("svc")).as("an hour this time").isTrue();
        clock.advanceMinutes(1);
        assertThat(breaker.isOpen("svc")).isFalse();
    }

    @Test
    void aTrialThatNeverReportsBackFreesAnotherAfterTheTrialTimeout() {
        fail(3);
        clock.advanceMinutes(15);
        assertThat(breaker.isOpen("svc")).isFalse(); // the trial is claimed and lost

        clock.now = clock.now.plus(CircuitBreaker.TRIAL_TIMEOUT).minusSeconds(1);
        assertThat(breaker.isOpen("svc")).isTrue();
        clock.now = clock.now.plusSeconds(1);
        assertThat(breaker.isOpen("svc")).as("a lost trial cannot wedge the breaker").isFalse();
        assertThat(breaker.isOpen("svc")).isTrue();
    }

    @Test
    void pausedObservesWithoutClaimingTheTrial() {
        fail(3);
        assertThat(breaker.paused("svc")).isTrue();
        clock.advanceMinutes(15);

        assertThat(breaker.paused("svc")).as("a call would be let through").isFalse();
        assertThat(breaker.paused("svc")).isFalse();
        assertThat(breaker.isOpen("svc")).as("the trial is still there to claim").isFalse();
        assertThat(breaker.paused("svc")).as("now it is claimed").isTrue();
    }

    // ---- sanitised settings (review item 4) ----

    @Test
    void aZeroRungIsDroppedSoTheBreakerStillPauses() {
        CircuitBreaker b = new CircuitBreaker(new CircuitBreakerProperties(1,
                List.of(Duration.ZERO, Duration.ofMinutes(-5), Duration.ofMinutes(1)), null), clock);

        b.failure("svc");

        assertThat(b.isOpen("svc")).as("a 0s pause would never open").isTrue();
    }
}
