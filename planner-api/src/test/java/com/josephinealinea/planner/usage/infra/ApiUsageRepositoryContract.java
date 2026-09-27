package com.josephinealinea.planner.usage.infra;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** What any {@link ApiUsageRepository} must do, run against YAML and PostgreSQL. */
public abstract class ApiUsageRepositoryContract {

    protected abstract ApiUsageRepository repository();

    private static String service() {
        return "svc-" + UUID.randomUUID();
    }

    @Test
    void callsAreAcquiredUpToTheCapAndNoFurther() {
        String service = service();

        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isTrue();
        assertThat(repository().tryAcquire(service, "2026-10", 3)).isFalse();

        assertThat(repository().calls(service, "2026-10")).isEqualTo(3);
    }

    @Test
    void aNewMonthStartsAtZeroWithNothingToReset() {
        String service = service();
        repository().tryAcquire(service, "2026-10", 1);
        assertThat(repository().tryAcquire(service, "2026-10", 1)).isFalse();

        assertThat(repository().tryAcquire(service, "2026-11", 1)).isTrue();
        assertThat(repository().calls(service, "2026-10")).as("history is kept").isEqualTo(1);
    }

    @Test
    void servicesAreCountedSeparately() {
        String a = service();
        String b = service();
        repository().tryAcquire(a, "2026-10", 1);

        assertThat(repository().tryAcquire(b, "2026-10", 1)).isTrue();
        assertThat(repository().calls(a, "2026-10")).isEqualTo(1);
    }

    @Test
    void aCapOfZeroAcquiresNothingAndWritesNothing() {
        String service = service();

        assertThat(repository().tryAcquire(service, "2026-10", 0)).isFalse();
        assertThat(repository().calls(service, "2026-10")).isZero();
    }

    @Test
    void anUnusedServiceHasZeroCalls() {
        assertThat(repository().calls(service(), "2026-10")).isZero();
    }

    /** Review focus 4: with one call left, exactly one of many racing callers gets it. */
    @Test
    void racingCallersCannotOvershootTheCap() throws Exception {
        String service = service();
        int cap = 5;
        int callers = 24;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<Boolean> call = () -> {
                go.await();
                return repository().tryAcquire(service, "2026-10", cap);
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        int granted = 0;
        for (Future<Boolean> result : results) if (result.get()) granted++;
        pool.shutdown();

        assertThat(granted).isEqualTo(cap);
        assertThat(repository().calls(service, "2026-10")).isEqualTo(cap);
    }
}
