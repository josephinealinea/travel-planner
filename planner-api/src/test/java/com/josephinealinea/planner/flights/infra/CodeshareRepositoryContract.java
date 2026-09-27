package com.josephinealinea.planner.flights.infra;

import com.josephinealinea.planner.flights.domain.CodeshareMapping;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class CodeshareRepositoryContract {

    protected abstract CodeshareRepository repository();

    private static String booked() {
        return "K" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    @Test
    void aSavedMappingIsFoundByTheBookedNumber() {
        String booked = booked();
        Instant at = Instant.parse("2026-09-26T09:14:22.480Z");

        repository().save(new CodeshareMapping(booked, "BT857", at));

        assertThat(repository().operatingFor(booked)).contains("BT857");
        assertThat(repository().findAll()).contains(new CodeshareMapping(booked, "BT857", at));
    }

    @Test
    void anUnknownNumberHasNoMapping() {
        assertThat(repository().operatingFor(booked())).isEmpty();
    }

    @Test
    void savingAgainReplacesTheMapping() {
        String booked = booked();
        repository().save(new CodeshareMapping(booked, "BT857", Instant.parse("2026-09-26T09:00:00Z")));
        repository().save(new CodeshareMapping(booked, "BT859", Instant.parse("2026-09-27T09:00:00Z")));

        assertThat(repository().operatingFor(booked)).contains("BT859");
    }
}
