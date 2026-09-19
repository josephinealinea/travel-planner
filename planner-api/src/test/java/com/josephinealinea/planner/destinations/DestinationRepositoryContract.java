package com.josephinealinea.planner.destinations;

import com.josephinealinea.planner.destinations.domain.Destination;
import com.josephinealinea.planner.destinations.infra.DestinationRepository;
import com.josephinealinea.planner.storage.EveryField;
import com.josephinealinea.planner.storage.TripScopedRepository;
import com.josephinealinea.planner.storage.TripScopedRepositoryContract;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Everything a {@link DestinationRepository} must do on top of the generic
 * per-trip contract, run against YAML and PostgreSQL alike: route order, and
 * a destination that comes back exactly as it was saved.
 */
public abstract class DestinationRepositoryContract extends TripScopedRepositoryContract<Destination> {

    protected abstract DestinationRepository store();

    @Override
    protected TripScopedRepository<Destination> repository() {
        return store();
    }

    /** The trip id PostgreSQL gives the trips it creates; YAML stores whatever it is handed. */
    protected static String tripIdOf(String slug) {
        return "trip-" + slug;
    }

    @Override
    protected Destination entity(String id, String label) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setTripId(tripIdOf(TRIP));
        destination.setName(label);
        return destination;
    }

    @Override
    protected String idOf(Destination entity) {
        return entity.getId();
    }

    @Override
    protected String labelOf(Destination entity) {
        return entity.getName();
    }

    // ---- ordering ----------------------------------------------------------

    @Test
    void routeOrderIsStartDateWithUndatedLastThenSortOrder() {
        store().save(TRIP, dated("undated-0", null, 0));
        store().save(TRIP, dated("25-oct-5", LocalDate.of(2026, 10, 25), 5));
        store().save(TRIP, dated("20-oct-9", LocalDate.of(2026, 10, 20), 9));
        store().save(TRIP, dated("25-oct-1", LocalDate.of(2026, 10, 25), 1));
        store().save(TRIP, dated("undated-minus-1", null, -1));

        assertThat(store().findAllOrdered(TRIP)).extracting(Destination::getId)
                .containsExactly("20-oct-9", "25-oct-1", "25-oct-5", "undated-minus-1", "undated-0");
    }

    @Test
    void aFullTieKeepsInsertionOrder() {
        store().save(TRIP, dated("b", LocalDate.of(2026, 10, 25), 0));
        store().save(TRIP, dated("a", LocalDate.of(2026, 10, 25), 0));

        assertThat(store().findAllOrdered(TRIP)).extracting(Destination::getId).containsExactly("b", "a");
    }

    // ---- round trip --------------------------------------------------------

    @Test
    void everyFieldComesBackAsItWasSaved() {
        Destination full = fullyPopulated();
        EveryField.assertEverySet(full);

        store().save(TRIP, full);

        assertThat(store().findById(TRIP, full.getId())).get()
                .usingRecursiveComparison()
                .isEqualTo(full);
    }

    @Test
    void aDestinationWithNothingButItsIdComesBackThatWay() {
        Destination bare = new Destination();
        bare.setId("bare");
        bare.setTripId(tripIdOf(TRIP));

        store().save(TRIP, bare);

        Destination loaded = store().findById(TRIP, "bare").orElseThrow();
        assertThat(loaded).usingRecursiveComparison().isEqualTo(bare);
        // Absent, not false: "never seeded" and "never suppressed" are states.
        assertThat(loaded.getLodgingSeeded()).isNull();
        assertThat(loaded.getSuppressChecklist()).isNull();
    }

    @Test
    void falseIsNotTheSameAsAbsent() {
        Destination d = entity("d", "Cusco");
        d.setLodgingSeeded(false);
        d.setSuppressChecklist(false);

        store().save(TRIP, d);

        Destination loaded = store().findById(TRIP, "d").orElseThrow();
        assertThat(loaded.getLodgingSeeded()).isFalse();
        assertThat(loaded.getSuppressChecklist()).isFalse();
    }

    /**
     * "Not set" follows the parent and "[]" is explicitly the whole trip, so
     * the store must keep them apart. See trips.api.Travellers.
     */
    @Test
    void notSetAndWholeTripAreDifferentTravellerStates() {
        Destination unset = entity("unset", "Uyuni");
        Destination everyone = entity("everyone", "La Paz");
        everyone.setTravellerIds(java.util.List.of());
        Destination named = entity("named", "Cusco");
        named.setTravellerIds(java.util.List.of("user-sam", "user-alex"));

        store().save(TRIP, unset);
        store().save(TRIP, everyone);
        store().save(TRIP, named);

        assertThat(store().findById(TRIP, "unset").orElseThrow().getTravellerIds()).isNull();
        assertThat(store().findById(TRIP, "everyone").orElseThrow().getTravellerIds()).isNotNull().isEmpty();
        // Order is kept: it is the order the buddies were picked in.
        assertThat(store().findById(TRIP, "named").orElseThrow().getTravellerIds())
                .containsExactly("user-sam", "user-alex");
    }

    private Destination dated(String id, LocalDate start, int sortOrder) {
        Destination d = entity(id, id);
        d.setStartDate(start);
        d.setSortOrder(sortOrder);
        return d;
    }

    static Destination fullyPopulated() {
        Destination d = new Destination();
        d.setId("dest-cusco");
        d.setTripId(tripIdOf(TRIP));
        d.setName("Cusco");
        d.setCountryCode("PE");
        d.setCountryName("Peru");
        d.setCountryFlag("🇵🇪");
        d.setLatitude(-13.53195);
        d.setLongitude(-71.967463);
        d.setGeonameId(3941584L);
        d.setTimezone("America/Lima");
        d.setStartDate(LocalDate.of(2026, 10, 25));
        d.setEndDate(LocalDate.of(2026, 10, 31));
        d.setNotes("Altitude: take it slow on day one.");
        d.setSortOrder(3);
        d.setLodgingSeeded(true);
        d.setSuppressChecklist(true);
        d.setCreatedAt(Instant.parse("2026-09-01T10:15:30.123456Z"));
        d.setCreatedByUserId("user-ana");
        d.setUpdatedAt(Instant.parse("2026-09-02T08:00:00Z"));
        d.setUpdatedByUserId("user-ben");
        d.setTravellerIds(java.util.List.of("user-ana", "user-ben"));
        return d;
    }
}
