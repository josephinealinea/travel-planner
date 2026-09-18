package com.josephinealinea.planner.storage.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test-only subclass of {@link TripScopedJdbcRepository} over a test-only
 * table, so the base class is proven against real PostgreSQL without writing
 * any module's repository — and exactly the shape a module's will take.
 *
 * Its columns cover every conversion {@link JdbcValues} offers: an ordered
 * {@code text[]}, nullable boolean / integer / double, an instant, a
 * wall-clock timestamp, a date and an enum.
 */
class NoteRepository extends TripScopedJdbcRepository<NoteRepository.Note> {

    enum Mood { CALM, EXCITED }

    /** A stand-in for a domain class: a mutable bean, like every real one. */
    static class Note {
        String id;
        String tripId;
        String text;
        List<String> tags = new ArrayList<>();
        Boolean flag;
        Integer count;
        Double ratio;
        Instant at;
        LocalDateTime wall;
        LocalDate day;
        Mood mood = Mood.CALM;

        Note(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS test_notes (
                trip_id text   NOT NULL REFERENCES trips (id) ON DELETE CASCADE,
                id      text   NOT NULL,
                seq     bigint GENERATED ALWAYS AS IDENTITY,
                text    text,
                tags    text[] NOT NULL DEFAULT '{}',
                flag    boolean,
                count   integer,
                ratio   double precision,
                at      timestamptz,
                wall    timestamp,
                day     date,
                mood    text   NOT NULL DEFAULT 'CALM',
                PRIMARY KEY (trip_id, id)
            )""";

    /** Idempotent, and deliberately outside Flyway: this table is not part of the app. */
    static void createTable(JdbcClient jdbc) {
        jdbc.sql(CREATE_TABLE).update();
    }

    NoteRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        super(jdbc, transactionManager, "test_notes",
                List.of("text", "tags", "flag", "count", "ratio", "at", "wall", "day", "mood"),
                note -> note.id);
    }

    @Override
    protected Map<String, Object> parametersOf(Note note) {
        // HashMap, not Map.of: most of these may be null.
        Map<String, Object> values = new HashMap<>();
        values.put("text", note.text);
        values.put("tags", JdbcValues.textArray(note.tags));
        values.put("flag", note.flag);
        values.put("count", note.count);
        values.put("ratio", note.ratio);
        values.put("at", JdbcValues.timestamptz(note.at));
        values.put("wall", note.wall);
        values.put("day", note.day);
        // NOT NULL DEFAULT 'CALM': a null field must be given the default explicitly.
        values.put("mood", JdbcValues.enumName(note.mood == null ? Mood.CALM : note.mood));
        return values;
    }

    @Override
    protected Note mapRow(ResultSet rs) throws SQLException {
        Note note = new Note(rs.getString("id"), rs.getString("text"));
        note.tripId = rs.getString("trip_id");
        note.tags = JdbcValues.textList(rs, "tags");
        note.flag = JdbcValues.nullableBoolean(rs, "flag");
        note.count = JdbcValues.nullableInteger(rs, "count");
        note.ratio = JdbcValues.nullableDouble(rs, "ratio");
        note.at = JdbcValues.instant(rs, "at");
        note.wall = JdbcValues.localDateTime(rs, "wall");
        note.day = JdbcValues.localDate(rs, "day");
        note.mood = JdbcValues.enumValue(rs, "mood", Mood.class, Mood.CALM);
        return note;
    }
}
