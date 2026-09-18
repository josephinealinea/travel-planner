package com.josephinealinea.planner.storage.jdbc;

import com.josephinealinea.planner.storage.TripScopedRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The database half of {@link TripScopedRepository}: one table per entity,
 * every row keyed {@code (trip_id, id)}, holding exactly the behaviour
 * {@code TripScopedYamlRepository} has for a YAML list. It is written once
 * here so the five per-trip tables cannot each get it slightly different.
 *
 * <table>
 *   <caption>YAML behaviour and its SQL equivalent</caption>
 *   <tr><th>YAML</th><th>here</th></tr>
 *   <tr><td>findAll in file order</td><td>{@code ORDER BY seq}</td></tr>
 *   <tr><td>save replaces in place, else appends</td>
 *       <td>{@code INSERT … ON CONFLICT (trip_id, id) DO UPDATE}, {@code seq} untouched</td></tr>
 *   <tr><td>saveAll is one write</td><td>the same upserts, one transaction</td></tr>
 *   <tr><td>replaceAll: the list becomes exactly this</td>
 *       <td>one transaction: delete the trip's rows, insert in the given order</td></tr>
 *   <tr><td>delete</td><td>{@code DELETE … WHERE trip_id = … AND id = …}</td></tr>
 * </table>
 *
 * <h2>What the table must look like</h2>
 * {@code trip_id text NOT NULL REFERENCES trips (id) ON DELETE CASCADE},
 * {@code id text NOT NULL}, {@code seq bigint GENERATED ALWAYS AS IDENTITY},
 * {@code PRIMARY KEY (trip_id, id)}, plus the entity's own columns. {@code seq}
 * is the list order: an upsert never sets it, so a replaced row keeps its
 * place, and replaceAll's re-insert is what gives the new order.
 *
 * <h2>The trip comes from the slug, never from the entity</h2>
 * Repositories are addressed by slug, as the YAML ones are by file name, and
 * every statement resolves it with {@code (SELECT id FROM trips WHERE slug =
 * :slug)}. The entity's own {@code tripId} field is never read: it is a copy,
 * and trusting it would let a row be filed under a trip nobody asked for. A
 * read for an unknown slug answers empty, like a missing file; a write to one
 * fails with {@link IllegalStateException}, because unlike a file a row cannot
 * exist without its trip.
 *
 * <h2>What a subclass supplies</h2>
 * <ul>
 *   <li>the table name, its entity columns (everything except {@code trip_id},
 *       {@code id} and {@code seq}) and how to read an entity's id — to the
 *       constructor;</li>
 *   <li>{@link #parametersOf}: one value per entity column, keyed by column
 *       name, using {@link JdbcValues} for arrays and instants. A column that
 *       is {@code NOT NULL DEFAULT …} must be given its default when the
 *       entity's field is null — an explicit null is not "use the
 *       default";</li>
 *   <li>{@link #mapRow}: the entity back from a row, using {@link JdbcValues}
 *       for anything nullable.</li>
 * </ul>
 * and carries {@code @Repository} plus {@code @ConditionalOnProperty(name =
 * "feature-enable-database", havingValue = "true")}, mirroring the YAML
 * implementation's annotation. This class is deliberately not a bean itself.
 *
 * <h2>Not here, on purpose</h2>
 * No locking — TripLocks exists because two rewrites of one file clobber each
 * other, which row upserts cannot. No audit stamping — the services stamp, not
 * the repositories (see CLAUDE.md, "Who did this").
 */
public abstract class TripScopedJdbcRepository<T> implements TripScopedRepository<T> {

    /** The trip-id lookup every statement uses. */
    protected static final String TRIP_ID_OF_SLUG = "(SELECT id FROM trips WHERE slug = :slug)";

    private static final Set<String> RESERVED = Set.of("trip_id", "id", "seq", "slug");

    protected final JdbcClient jdbc;
    protected final TransactionTemplate transactions;
    protected final String table;

    private final List<String> columns;
    private final Function<T, String> idOf;

    private final String selectAll;
    private final String selectOne;
    private final String upsert;
    private final String deleteOne;
    private final String deleteAll;

    /**
     * @param table   the table name, e.g. {@code "destinations"}
     * @param columns the entity columns, excluding {@code trip_id}, {@code id}
     *                and {@code seq}; also the parameter names
     *                {@link #parametersOf} must supply
     * @param idOf    how to read an entity's id
     */
    protected TripScopedJdbcRepository(JdbcClient jdbc,
                                       PlatformTransactionManager transactionManager,
                                       String table,
                                       List<String> columns,
                                       Function<T, String> idOf) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.table = table;
        this.columns = List.copyOf(columns);
        this.idOf = idOf;

        Set<String> clash = new LinkedHashSet<>(this.columns);
        clash.retainAll(RESERVED);
        if (!clash.isEmpty()) {
            throw new IllegalArgumentException(table + ": " + clash
                    + " are managed by TripScopedJdbcRepository and must not be listed as columns");
        }
        if (new LinkedHashSet<>(this.columns).size() != this.columns.size()) {
            throw new IllegalArgumentException(table + ": a column is listed twice in " + this.columns);
        }

        String names = this.columns.isEmpty() ? "" : ", " + String.join(", ", this.columns);
        String values = this.columns.stream().map(c -> ":" + c).collect(Collectors.joining(", "));
        String valueList = this.columns.isEmpty() ? "" : ", " + values;

        this.selectAll = "SELECT * FROM " + table + " WHERE trip_id = " + TRIP_ID_OF_SLUG + " ORDER BY seq";
        this.selectOne = "SELECT * FROM " + table + " WHERE trip_id = " + TRIP_ID_OF_SLUG + " AND id = :id";
        this.upsert = "INSERT INTO " + table + " (trip_id, id" + names + ") VALUES (:trip_id, :id" + valueList + ")"
                + " ON CONFLICT (trip_id, id) DO "
                + (this.columns.isEmpty()
                        ? "NOTHING"
                        : "UPDATE SET " + this.columns.stream()
                                .map(c -> c + " = EXCLUDED." + c)
                                .collect(Collectors.joining(", ")));
        this.deleteOne = "DELETE FROM " + table + " WHERE trip_id = " + TRIP_ID_OF_SLUG + " AND id = :id";
        this.deleteAll = "DELETE FROM " + table + " WHERE trip_id = :trip_id";
    }

    /**
     * One value per entry in {@code columns}, keyed by column name. Values are
     * bound as JDBC parameters: use {@link JdbcValues#textArray} for a
     * {@code text[]}, {@link JdbcValues#timestamptz} for an Instant and
     * {@link JdbcValues#enumName} for an enum.
     */
    protected abstract Map<String, Object> parametersOf(T entity);

    /** The entity from the current row. {@code trip_id}, {@code id} and {@code seq} are readable too. */
    protected abstract T mapRow(ResultSet rs) throws SQLException;

    @Override
    public List<T> findAll(String tripSlug) {
        return jdbc.sql(selectAll)
                .param("slug", tripSlug)
                .query((rs, n) -> mapRow(rs))
                .list();
    }

    @Override
    public Optional<T> findById(String tripSlug, String id) {
        return jdbc.sql(selectOne)
                .param("slug", tripSlug)
                .param("id", id)
                .query((rs, n) -> mapRow(rs))
                .optional();
    }

    @Override
    public T save(String tripSlug, T entity) {
        transactions.executeWithoutResult(status -> write(upsert, requireTripId(tripSlug), entity));
        return entity;
    }

    @Override
    public List<T> saveAll(String tripSlug, List<T> entities) {
        if (entities.isEmpty()) return entities;
        transactions.executeWithoutResult(status -> {
            String tripId = requireTripId(tripSlug);
            for (T entity : entities) write(upsert, tripId, entity);
        });
        return entities;
    }

    @Override
    public void delete(String tripSlug, String id) {
        jdbc.sql(deleteOne)
                .param("slug", tripSlug)
                .param("id", id)
                .update();
    }

    /**
     * Deletes the trip's rows and re-inserts the given ones, in order, in one
     * transaction — so the new list order becomes the new {@code seq} order,
     * and a failure part-way leaves the old list intact.
     */
    @Override
    public void replaceAll(String tripSlug, List<T> items) {
        transactions.executeWithoutResult(status -> {
            String tripId = requireTripId(tripSlug);
            jdbc.sql(deleteAll).param("trip_id", tripId).update();
            // Upsert rather than plain insert, so a list naming one id twice
            // collapses to its last value exactly as saveAll would.
            for (T item : items) write(upsert, tripId, item);
        });
    }

    /**
     * The trip's id for a write. Resolved once per transaction rather than per
     * row, from the slug — never from the entity.
     */
    protected String requireTripId(String tripSlug) {
        return jdbc.sql("SELECT id FROM trips WHERE slug = :slug")
                .param("slug", tripSlug)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "No trip with slug '" + tripSlug + "' — cannot write to " + table));
    }

    private void write(String sql, String tripId, T entity) {
        Map<String, Object> values = parametersOf(entity);
        List<String> missing = new ArrayList<>(columns);
        missing.removeAll(values.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException(table + ": parametersOf supplied no value for " + missing);
        }
        Map<String, Object> params = new HashMap<>(values);
        params.put("trip_id", tripId);
        params.put("id", idOf.apply(entity));
        jdbc.sql(sql).params(params).update();
    }

    /** The configured entity columns, in declaration order. */
    protected List<String> columns() {
        return columns;
    }
}
