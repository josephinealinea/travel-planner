package com.josephinealinea.planner.weather;

import com.josephinealinea.planner.destinations.PerTripStores;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import com.josephinealinea.planner.weather.domain.WeatherRecord;
import com.josephinealinea.planner.weather.infra.WeatherRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a {@link WeatherRepository} must do on top of the generic
 * per-trip contract, run against YAML and PostgreSQL alike.
 *
 * The interface adds no finders — WeatherService matches on rounded
 * coordinates and date itself — so what is pinned here is the record, the
 * id that repeats across trips, and nulls that must not turn into zeroes.
 */
public abstract class WeatherRepositoryContract extends TripScopedRepositoryContract<WeatherRecord> {

    private static final LocalDate OCT_25 = LocalDate.of(2026, 10, 25);

    /**
     * A weather record has no free-text field to carry the generic contract's
     * "label", so each label is given a weather code and remembered here.
     */
    private final Map<Integer, String> labels = new HashMap<>();

    protected abstract WeatherRepository store();

    @Override
    protected TripScopedRepository<WeatherRecord> repository() {
        return store();
    }

    @Override
    protected WeatherRecord entity(String id, String label) {
        int code = labels.entrySet().stream()
                .filter(e -> e.getValue().equals(label))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseGet(() -> {
                    int next = labels.size() + 1;
                    labels.put(next, label);
                    return next;
                });
        WeatherRecord record = reading(-13.53, -71.97, OCT_25);
        record.setId(id);
        record.setWeatherCode(code);
        return record;
    }

    @Override
    protected String idOf(WeatherRecord entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(WeatherRecord entity) {
        return labels.get(entity.getWeatherCode());
    }

    // ---- the key ----------------------------------------------------------

    @Test
    void twoTripsThroughTheSamePlaceOnTheSameDayHoldARecordEach() {
        WeatherRecord forOneTrip = reading(-13.53, -71.97, OCT_25);
        forOneTrip.setTemperatureMax(19.5);
        WeatherRecord forAnotherTrip = reading(-13.53, -71.97, OCT_25);
        forAnotherTrip.setTemperatureMax(21.0);
        assertThat(forOneTrip.getId()).isEqualTo(forAnotherTrip.getId());

        store().save(TRIP, forOneTrip);
        store().save(OTHER_TRIP, forAnotherTrip);
        store().replaceAll(OTHER_TRIP, List.of(forAnotherTrip));

        assertThat(store().findAll(TRIP)).singleElement()
                .extracting(WeatherRecord::getTemperatureMax).isEqualTo(19.5);
        assertThat(store().findAll(OTHER_TRIP)).singleElement()
                .extracting(WeatherRecord::getTemperatureMax).isEqualTo(21.0);

        store().delete(TRIP, forOneTrip.getId());
        assertThat(store().findAll(TRIP)).isEmpty();
        assertThat(store().findAll(OTHER_TRIP)).hasSize(1);
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void everyFieldComesBackAsItWasSaved() {
        WeatherRecord full = fullyPopulated();
        PerTripStores.assertEveryStoredFieldIsSet(full);

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .isEqualTo(full);
    }

    /**
     * Only the key — coordinates and date — is required. A missing reading is
     * the climate endpoint's column of nulls; reading it back as 0 would
     * invent a clear sky (WMO code 0) and a dry, freezing day.
     */
    @Test
    void aRecordWithOnlyItsKeyComesBackWithNullsNotZeroes() {
        WeatherRecord bare = reading(-13.53, -71.97, OCT_25);

        store().save(TRIP, bare);

        WeatherRecord loaded = store().findById(TRIP, bare.getId()).orElseThrow();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(bare);
        assertThat(loaded.getWeatherCode()).isNull();
        assertThat(loaded.getPrecipitation()).isNull();
        assertThat(loaded.getSource()).isNull();
    }

    @Test
    void aZeroReadingIsStillZero() {
        WeatherRecord clear = reading(-16.5, -68.15, OCT_25);
        clear.setWeatherCode(0);
        clear.setPrecipitation(0.0);
        clear.setTemperatureMin(0.0);

        store().save(TRIP, clear);

        WeatherRecord loaded = store().findById(TRIP, clear.getId()).orElseThrow();
        assertThat(loaded.getWeatherCode()).isZero();
        assertThat(loaded.getPrecipitation()).isZero();
        assertThat(loaded.getTemperatureMin()).isZero();
    }

    static WeatherRecord reading(double latitude, double longitude, LocalDate date) {
        WeatherRecord record = new WeatherRecord();
        record.setId(WeatherRecord.keyFor(latitude, longitude, date));
        record.setLatitude(latitude);
        record.setLongitude(longitude);
        record.setDate(date);
        return record;
    }

    static WeatherRecord fullyPopulated() {
        WeatherRecord record = reading(-13.531950, -71.967463, OCT_25);
        record.setWeatherCode(61);
        record.setTemperatureMax(19.4);
        record.setTemperatureMin(3.1);
        record.setPrecipitation(2.75);
        record.setSource(DayWeather.Source.CLIMATE);
        record.setFetchedAt(Instant.parse("2026-09-18T06:12:45.123456Z"));
        return record;
    }
}
