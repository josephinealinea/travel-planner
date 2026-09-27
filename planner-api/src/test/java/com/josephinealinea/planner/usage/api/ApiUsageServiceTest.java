package com.josephinealinea.planner.usage.api;

import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiUsageServiceTest {

    /** In-memory stand-in: the month arrives as part of the key. */
    private static final class Memory implements ApiUsageRepository {
        final Map<String, Integer> counts = new HashMap<>();

        @Override
        public boolean tryAcquire(String service, String month, int cap) {
            int now = counts.getOrDefault(service + "|" + month, 0);
            if (now >= cap) return false;
            counts.put(service + "|" + month, now + 1);
            return true;
        }

        @Override
        public int calls(String service, String month) {
            return counts.getOrDefault(service + "|" + month, 0);
        }
    }

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    void monthIsTheUtcCalendarMonth() {
        assertThat(ApiUsageService.monthOf(Instant.parse("2026-10-31T23:59:59Z"))).isEqualTo("2026-10");
        assertThat(ApiUsageService.monthOf(Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo("2026-11");
    }

    @Test
    void aNewMonthNeedsNoResetJob() {
        Memory memory = new Memory();
        assertThat(new ApiUsageService(memory, at("2026-10-31T23:59:00Z")).tryAcquire("aerodatabox", 1)).isTrue();
        assertThat(new ApiUsageService(memory, at("2026-10-31T23:59:30Z")).tryAcquire("aerodatabox", 1)).isFalse();

        ApiUsageService november = new ApiUsageService(memory, at("2026-11-01T00:00:01Z"));
        assertThat(november.tryAcquire("aerodatabox", 1)).isTrue();
        assertThat(november.calls("aerodatabox")).isEqualTo(1);
    }

    @Test
    void dayIsTheUtcCalendarDay() {
        assertThat(ApiUsageService.dayOf(Instant.parse("2026-09-27T23:59:59Z"))).isEqualTo("2026-09-27");
        assertThat(ApiUsageService.dayOf(Instant.parse("2026-09-28T00:00:00Z"))).isEqualTo("2026-09-28");
    }

    @Test
    void aNewDayNeedsNoResetJob() {
        Memory memory = new Memory();
        assertThat(new ApiUsageService(memory, at("2026-09-27T23:59:00Z")).tryAcquireDaily("newsdata", 1)).isTrue();
        assertThat(new ApiUsageService(memory, at("2026-09-27T23:59:30Z")).tryAcquireDaily("newsdata", 1)).isFalse();

        ApiUsageService tomorrow = new ApiUsageService(memory, at("2026-09-28T00:00:01Z"));
        assertThat(tomorrow.tryAcquireDaily("newsdata", 1)).isTrue();
        assertThat(tomorrow.callsToday("newsdata")).isEqualTo(1);
    }
}
