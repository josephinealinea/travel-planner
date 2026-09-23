package com.josephinealinea.planner.storage.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.jdbc.core.support.AbstractSqlTypeValue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conversions between the domain's Java types and PostgreSQL columns, in both
 * directions, for every JDBC repository to share.
 *
 * Each exists because the obvious call gets something subtly wrong:
 *
 * <ul>
 *   <li><b>{@code text[]} keeps its order.</b> Order is data here — the first
 *       sharers of a budget row take the leftover cent — so a list goes in and
 *       comes out as the same sequence, and a SQL {@code NULL} reads as an
 *       empty list, the same as a key absent from a YAML file.</li>
 *   <li><b>A null column stays null.</b> {@code rs.getBoolean} answers
 *       {@code false} for {@code NULL}, and {@code getInt} answers {@code 0};
 *       both are real values in this domain ({@code lodgingSeeded},
 *       {@code allDay}, a WMO weather code of 0 means "clear sky"), so the
 *       nullable readers check {@code wasNull()}.</li>
 *   <li><b>An Instant is a {@code timestamptz}, a LocalDateTime is a
 *       {@code timestamp}.</b> Itinerary times are wall-clock at the
 *       destination, so they must round-trip untouched by any time zone; audit
 *       stamps are instants and are written at UTC so the session's zone never
 *       matters.</li>
 * </ul>
 */
public final class JdbcValues {

    /**
     * Its own mapper rather than the application's: what a {@code jsonb}
     * column holds is a storage format, and it must not start reading
     * differently because a controller's serialisation was reconfigured.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JdbcValues() {}

    // ---- parameters: Java -> SQL -------------------------------------------

    /**
     * A {@code text[]} parameter, in list order. Null or empty becomes an empty
     * array, matching the {@code NOT NULL DEFAULT '{}'} array columns.
     */
    public static SqlTypeValue textArray(List<String> values) {
        String[] array = values == null ? new String[0] : values.toArray(String[]::new);
        return new AbstractSqlTypeValue() {
            @Override
            protected Object createTypeValue(Connection con, int sqlType, String typeName) throws SQLException {
                return con.createArrayOf("text", array);
            }
        };
    }

    /**
     * A nullable {@code text[]} parameter. Null stays SQL NULL — for the
     * traveller columns that is a state ("follow the parent"), not an empty
     * list — while any list, even an empty one, becomes an array.
     */
    public static Object nullableTextArray(List<String> values) {
        return values == null ? new SqlParameterValue(Types.ARRAY, null) : textArray(values);
    }

    /** A {@code timestamptz} parameter. PgJDBC does not bind a bare Instant. */
    public static OffsetDateTime timestamptz(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** An enum as the {@code text} it is stored as: its name, or null. */
    public static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * An object as the JSON a {@code jsonb} column holds.
     *
     * Bound as text and cast in the statement ({@code :param::jsonb}) rather
     * than through a driver-specific type, so nothing here depends on PgJDBC
     * being on the compile classpath.
     */
    public static String json(Object value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write " + value.getClass().getSimpleName() + " as JSON", e);
        }
    }

    // ---- columns: SQL -> Java ----------------------------------------------

    /** A {@code text[]} column as a mutable list in stored order; {@code NULL} reads as empty. */
    public static List<String> textList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return new ArrayList<>();
        try {
            return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }

    /** A nullable {@code text[]} column: NULL reads as null, never as an empty list. */
    public static List<String> nullableTextList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return null;
        try {
            return new ArrayList<>(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }

    public static Boolean nullableBoolean(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    public static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    public static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * A {@code jsonb} column back into its object. SQL NULL and an empty
     * document both read as null, so the field keeps whatever initialiser its
     * class gave it — the same as a key absent from a YAML file.
     */
    public static <T> T json(ResultSet rs, String column, Class<T> type) throws SQLException {
        String value = rs.getString(column);
        if (value == null || value.isBlank()) return null;
        try {
            return JSON.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read " + column + " as " + type.getSimpleName(), e);
        }
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** A {@code timestamp} (no zone) column: wall-clock time, returned exactly as stored. */
    public static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class);
    }

    public static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    /**
     * An enum column. {@code NULL} reads as the given fallback, which should be
     * the field's own initialiser in the domain class — the same answer a YAML
     * file with the key absent gives.
     */
    public static <E extends Enum<E>> E enumValue(ResultSet rs, String column, Class<E> type, E fallback)
            throws SQLException {
        String value = rs.getString(column);
        return value == null ? fallback : Enum.valueOf(type, value);
    }
}
