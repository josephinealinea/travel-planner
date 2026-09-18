package com.josephinealinea.planner.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link TripScopedRepository} must do, whichever store is behind
 * it. One subclass per implementation; if YAML and PostgreSQL ever disagree
 * about any of this, one of them fails.
 *
 * These are the behaviours services lean on without saying so — insertion
 * order as the tie-break budget and weather rows have no other, an upsert that
 * keeps a row's place, a replaceAll that is exact — so they are pinned here
 * rather than rediscovered through a budget that sorts differently in
 * production.
 *
 * <h2>Writing a subclass</h2>
 * Supply the repository, a way to make an entity with a given id and a
 * "label" (any field that can be changed and read back), and
 * {@link #givenTrip}: nothing for YAML, a {@code trips} row for PostgreSQL.
 * Trips {@link #TRIP} and {@link #OTHER_TRIP} are set up before every test.
 */
public abstract class TripScopedRepositoryContract<T> {

    protected static final String TRIP = "lima-2026";
    protected static final String OTHER_TRIP = "cusco-2026";

    /** The implementation under test, fresh or emptied for each test. */
    protected abstract TripScopedRepository<T> repository();

    /** Makes the trip exist in the store, if the store needs that. */
    protected abstract void givenTrip(String slug);

    protected abstract T entity(String id, String label);

    protected abstract String idOf(T entity);

    protected abstract String labelOf(T entity);

    @BeforeEach
    void twoTrips() {
        givenTrip(TRIP);
        givenTrip(OTHER_TRIP);
    }

    @Test
    void aTripWithNothingSavedIsEmpty() {
        assertThat(repository().findAll(TRIP)).isEmpty();
        assertThat(repository().findById(TRIP, "a")).isEmpty();
    }

    @Test
    void aSlugNobodyKnowsReadsAsEmpty() {
        assertThat(repository().findAll("no-such-trip")).isEmpty();
        assertThat(repository().findById("no-such-trip", "a")).isEmpty();
    }

    @Test
    void findAllReturnsRowsInTheOrderTheyWereFirstSaved() {
        repository().save(TRIP, entity("c", "third"));
        repository().save(TRIP, entity("a", "first"));
        repository().save(TRIP, entity("b", "second"));

        assertThat(ids(repository().findAll(TRIP))).containsExactly("c", "a", "b");
    }

    @Test
    void savingAnExistingIdReplacesItWhereItStands() {
        repository().save(TRIP, entity("a", "one"));
        repository().save(TRIP, entity("b", "two"));
        repository().save(TRIP, entity("c", "three"));

        repository().save(TRIP, entity("a", "one, corrected"));

        List<T> all = repository().findAll(TRIP);
        assertThat(ids(all)).containsExactly("a", "b", "c");
        assertThat(labelOf(all.get(0))).isEqualTo("one, corrected");
        assertThat(repository().findById(TRIP, "a")).map(this::labelOf).contains("one, corrected");
    }

    @Test
    void saveAllAppendsTheNewAndReplacesTheExistingInPlace() {
        repository().save(TRIP, entity("a", "one"));
        repository().save(TRIP, entity("b", "two"));

        repository().saveAll(TRIP, List.of(entity("c", "three"), entity("a", "one again"), entity("d", "four")));

        List<T> all = repository().findAll(TRIP);
        assertThat(ids(all)).containsExactly("a", "b", "c", "d");
        assertThat(labels(all)).containsExactly("one again", "two", "three", "four");
    }

    @Test
    void saveAllOfNothingChangesNothing() {
        repository().save(TRIP, entity("a", "one"));

        assertThat(repository().saveAll(TRIP, List.of())).isEmpty();

        assertThat(ids(repository().findAll(TRIP))).containsExactly("a");
    }

    @Test
    void replaceAllMakesTheListExactlyTheGivenOne() {
        repository().saveAll(TRIP, List.of(entity("a", "one"), entity("b", "two"), entity("c", "three")));

        // b is dropped, c and a swap, d is new, a is edited.
        repository().replaceAll(TRIP, List.of(entity("c", "three"), entity("d", "four"), entity("a", "one, edited")));

        List<T> all = repository().findAll(TRIP);
        assertThat(ids(all)).containsExactly("c", "d", "a");
        assertThat(labels(all)).containsExactly("three", "four", "one, edited");
        assertThat(repository().findById(TRIP, "b")).isEmpty();
    }

    @Test
    void aRowSavedAfterReplaceAllGoesAfterTheReplacedList() {
        repository().saveAll(TRIP, List.of(entity("a", "one"), entity("b", "two")));
        repository().replaceAll(TRIP, List.of(entity("b", "two"), entity("a", "one")));

        repository().save(TRIP, entity("c", "three"));
        repository().save(TRIP, entity("b", "two, edited"));

        assertThat(ids(repository().findAll(TRIP))).containsExactly("b", "a", "c");
    }

    @Test
    void replaceAllWithNothingEmptiesTheTrip() {
        repository().saveAll(TRIP, List.of(entity("a", "one"), entity("b", "two")));

        repository().replaceAll(TRIP, List.of());

        assertThat(repository().findAll(TRIP)).isEmpty();
    }

    @Test
    void deleteRemovesThatRowAndKeepsTheOthersInOrder() {
        repository().saveAll(TRIP, List.of(entity("a", "one"), entity("b", "two"), entity("c", "three")));

        repository().delete(TRIP, "b");

        assertThat(ids(repository().findAll(TRIP))).containsExactly("a", "c");
    }

    @Test
    void deletingAnIdThatIsNotThereIsHarmless() {
        repository().save(TRIP, entity("a", "one"));

        repository().delete(TRIP, "nope");

        assertThat(ids(repository().findAll(TRIP))).containsExactly("a");
    }

    @Test
    void theSameIdInTwoTripsIsTwoRows() {
        // Weather ids ("lat,lon:date") repeat across trips by design.
        repository().save(TRIP, entity("shared", "in lima"));
        repository().save(OTHER_TRIP, entity("shared", "in cusco"));

        assertThat(repository().findById(TRIP, "shared")).map(this::labelOf).contains("in lima");
        assertThat(repository().findById(OTHER_TRIP, "shared")).map(this::labelOf).contains("in cusco");
    }

    @Test
    void noWriteReachesAnotherTrip() {
        repository().saveAll(OTHER_TRIP, List.of(entity("a", "cusco a"), entity("b", "cusco b")));
        repository().saveAll(TRIP, List.of(entity("a", "lima a"), entity("b", "lima b")));

        repository().delete(TRIP, "a");
        repository().replaceAll(TRIP, List.of(entity("z", "lima z")));
        repository().save(TRIP, entity("b", "lima b again"));

        assertThat(ids(repository().findAll(OTHER_TRIP))).containsExactly("a", "b");
        assertThat(labels(repository().findAll(OTHER_TRIP))).containsExactly("cusco a", "cusco b");
        assertThat(ids(repository().findAll(TRIP))).containsExactly("z", "b");
    }

    private List<String> ids(List<T> entities) {
        return entities.stream().map(this::idOf).toList();
    }

    private List<String> labels(List<T> entities) {
        return entities.stream().map(this::labelOf).toList();
    }
}
