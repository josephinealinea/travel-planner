package com.josephinealinea.planner.usage.api;

import com.josephinealinea.planner.usage.infra.ApiUsageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Counts outbound calls against a monthly cap. The month is the UTC calendar
 * month, so a new month is simply a new key and no job has to reset anything.
 */
@Service
public class ApiUsageService {

    private final ApiUsageRepository repository;
    private final Clock clock;

    @Autowired // two constructors: Spring must be told which one is real
    public ApiUsageService(ApiUsageRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ApiUsageService(ApiUsageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** True when the call may go out; it is counted when granted, even if it then fails. */
    public boolean tryAcquire(String service, int cap) {
        return repository.tryAcquire(service, monthOf(clock.instant()), cap);
    }

    public int calls(String service) {
        return repository.calls(service, monthOf(clock.instant()));
    }

    static String monthOf(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        return "%04d-%02d".formatted(utc.getYear(), utc.getMonthValue());
    }
}
