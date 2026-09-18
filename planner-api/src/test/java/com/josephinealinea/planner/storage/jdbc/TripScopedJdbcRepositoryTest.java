package com.josephinealinea.planner.storage.jdbc;

import com.josephinealinea.planner.storage.jdbc.NoteRepository.Mood;
import com.josephinealinea.planner.storage.jdbc.NoteRepository.Note;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What TripScopedJdbcRepository does beyond the shared contract: the column
 * conversions, where the trip comes from, and what happens when a write fails
 * half-way. All against real PostgreSQL through the test-only NoteRepository.
 */
class TripScopedJdbcRepositoryTest extends PostgresTestBase {

    private static final String LIMA = "lima-2026";

    private NoteRepository notes;

    @BeforeAll
    static void table() {
        NoteRepository.createTable(jdbc());
    }

    @BeforeEach
    void trip() {
        givenTrip("trip-lima", LIMA);
        givenTrip("trip-cusco", "cusco-2026");
        notes = new NoteRepository(jdbc(), transactionManager());
    }

    @Test
    void everyColumnTypeRoundTrips() {
        Note note = new Note("n1", "Pack for the altitude");
        note.tags = List.of("zeta", "alpha", "mid", "alpha");   // order and duplicates kept
        note.flag = false;
        note.count = 0;
        note.ratio = 0.0;
        note.at = Instant.parse("2026-10-24T05:30:00.123456Z");
        note.wall = LocalDateTime.of(2026, 10, 25, 6, 0);
        note.day = LocalDate.of(2026, 10, 25);
        note.mood = Mood.EXCITED;

        notes.save(LIMA, note);
        Note read = notes.findById(LIMA, "n1").orElseThrow();

        assertThat(read.text).isEqualTo("Pack for the altitude");
        assertThat(read.tags).containsExactly("zeta", "alpha", "mid", "alpha");
        // false and 0 are values, not absence: they must not read back as null.
        assertThat(read.flag).isFalse();
        assertThat(read.count).isZero();
        assertThat(read.ratio).isZero();
        assertThat(read.at).isEqualTo(note.at);
        assertThat(read.day).isEqualTo(note.day);
        assertThat(read.mood).isEqualTo(Mood.EXCITED);
    }

    @Test
    void nullsStayNullAndAnEmptyListStaysEmpty() {
        Note note = new Note("n1", null);
        note.tags = null;
        note.mood = null;

        notes.save(LIMA, note);
        Note read = notes.findById(LIMA, "n1").orElseThrow();

        assertThat(read.text).isNull();
        assertThat(read.tags).isEmpty();
        assertThat(read.flag).isNull();
        assertThat(read.count).isNull();
        assertThat(read.ratio).isNull();
        assertThat(read.at).isNull();
        assertThat(read.wall).isNull();
        assertThat(read.day).isNull();
        assertThat(read.mood).isEqualTo(Mood.CALM);
    }

    @Test
    void aWallClockTimeIsNotShiftedByAnyTimeZone() {
        // An itinerary's 06:00 departure is 06:00 at the destination, whatever
        // zone the server or the database session happens to run in.
        JdbcClient jdbc = jdbc();
        Note note = new Note("n1", "Bus to Puno");
        note.wall = LocalDateTime.of(2026, 10, 27, 6, 0);
        notes.save(LIMA, note);

        String stored = jdbc.sql("SELECT wall::text FROM test_notes WHERE id = 'n1'").query(String.class).single();

        assertThat(stored).isEqualTo("2026-10-27 06:00:00");
        assertThat(notes.findById(LIMA, "n1").orElseThrow().wall).isEqualTo(note.wall);
    }

    @Test
    void anInstantIsKeptToTheMicrosecond() {
        Note note = new Note("n1", "stamp");
        note.at = Instant.now().truncatedTo(ChronoUnit.MICROS);

        notes.save(LIMA, note);

        assertThat(notes.findById(LIMA, "n1").orElseThrow().at).isEqualTo(note.at);
    }

    @Test
    void theTripComesFromTheSlugNeverFromTheEntity() {
        Note note = new Note("n1", "misfiled?");
        note.tripId = "trip-cusco";

        notes.save(LIMA, note);

        assertThat(notes.findAll("cusco-2026")).isEmpty();
        assertThat(notes.findById(LIMA, "n1").orElseThrow().tripId).isEqualTo("trip-lima");
    }

    @Test
    void writingToATripThatDoesNotExistFailsClearly() {
        assertThatThrownBy(() -> notes.save("no-such-trip", new Note("n1", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-trip");
        assertThatThrownBy(() -> notes.replaceAll("no-such-trip", List.of(new Note("n1", "x"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUpsertLeavesTheRowsPositionAlone() {
        notes.saveAll(LIMA, List.of(new Note("a", "1"), new Note("b", "2")));
        long before = seqOf("a");

        notes.save(LIMA, new Note("a", "1, edited"));

        assertThat(seqOf("a")).isEqualTo(before);
    }

    @Test
    void aReplaceAllThatFailsPartWayLeavesTheOldListIntact() {
        notes.saveAll(LIMA, List.of(new Note("a", "1"), new Note("b", "2")));

        Note broken = new Note("c", "3");
        NoteRepository failing = new NoteRepository(jdbc(), transactionManager()) {
            @Override
            protected Map<String, Object> parametersOf(Note note) {
                if (note == broken) throw new IllegalStateException("boom");
                return super.parametersOf(note);
            }
        };

        assertThatThrownBy(() -> failing.replaceAll(LIMA, List.of(new Note("z", "26"), broken)))
                .hasMessage("boom");

        assertThat(notes.findAll(LIMA)).extracting(n -> n.id).containsExactly("a", "b");
    }

    @Test
    void aSaveAllThatFailsPartWayWritesNothing() {
        Note broken = new Note("c", "3");
        NoteRepository failing = new NoteRepository(jdbc(), transactionManager()) {
            @Override
            protected Map<String, Object> parametersOf(Note note) {
                if (note == broken) throw new IllegalStateException("boom");
                return super.parametersOf(note);
            }
        };

        assertThatThrownBy(() -> failing.saveAll(LIMA, List.of(new Note("a", "1"), broken)))
                .hasMessage("boom");

        assertThat(notes.findAll(LIMA)).isEmpty();
    }

    @Test
    void deletingTheTripDeletesItsRows() {
        notes.saveAll(LIMA, List.of(new Note("a", "1"), new Note("b", "2")));

        jdbc().sql("DELETE FROM trips WHERE slug = :slug").param("slug", LIMA).update();

        assertThat(jdbc().sql("SELECT count(*) FROM test_notes").query(Long.class).single()).isZero();
    }

    @Test
    void aSubclassMayNotClaimTheManagedColumns() {
        assertThatThrownBy(() -> new TripScopedJdbcRepository<Note>(jdbc(), transactionManager(),
                "test_notes", List.of("text", "seq"), n -> n.id) {
            @Override
            protected Map<String, Object> parametersOf(Note entity) {
                return Map.of();
            }

            @Override
            protected Note mapRow(java.sql.ResultSet rs) {
                return null;
            }
        }).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seq");
    }

    @Test
    void aMissingParameterIsNamed() {
        NoteRepository forgetful = new NoteRepository(jdbc(), transactionManager()) {
            @Override
            protected Map<String, Object> parametersOf(Note note) {
                Map<String, Object> values = super.parametersOf(note);
                values.remove("ratio");
                return values;
            }
        };

        assertThatThrownBy(() -> forgetful.save(LIMA, new Note("a", "1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ratio");
    }

    private long seqOf(String id) {
        return jdbc().sql("SELECT seq FROM test_notes WHERE id = :id").param("id", id).query(Long.class).single();
    }
}
