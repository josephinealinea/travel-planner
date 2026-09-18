package com.josephinealinea.planner.trips.infra;

import com.josephinealinea.planner.publish.domain.PublishRequest;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import com.josephinealinea.planner.trips.domain.Trip;
import com.josephinealinea.planner.trips.domain.TripMember;
import com.josephinealinea.planner.trips.domain.TripRole;
import com.josephinealinea.planner.trips.domain.TripStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Trips in PostgreSQL: the trip's own row in {@code trips}, its members in
 * {@code trip_members} and its publish requests in {@code publish_requests}.
 * They are read and written together, as the one YAML file per trip is, and
 * the contract test runs the same assertions against both stores.
 *
 * <h2>How it matches {@link YamlTripRepository}</h2>
 * <ul>
 *   <li><b>{@link #save} stamps both timestamps, every time</b> —
 *       {@code updatedAt} always, {@code createdAt} when absent. Trip is the
 *       one record whose repository stamps its own times (CLAUDE.md, "Who did
 *       this"), and that stays true here.</li>
 *   <li><b>Member order is kept, because it is money.</b> Leftover cents go
 *       to the earliest sharers, and a budget row shared by nobody resolves to
 *       the members in list order. {@code trip_members.position} is the list
 *       index, rewritten on every save, so a reorder is stored as one.
 *       Publish requests carry a {@code position} the same way.</li>
 *   <li><b>Children are replaced wholesale.</b> A save upserts the trip row,
 *       then deletes and re-inserts its members and requests in list order —
 *       all in one transaction, so a reader never sees a trip with half its
 *       members, and a member removed from the list is gone from the table.</li>
 *   <li><b>{@link #findAllForUser} lists the trips the user is a member
 *       of</b>, latest start date first and undated trips last — the same
 *       filter the YAML version applies ({@code Trip.isMember}). Owning a trip
 *       without being in its member list does not list it; that never
 *       happens, because {@code TripService.create} adds the owner as the
 *       first member.</li>
 *   <li><b>{@link #delete} is one statement.</b> Every per-trip table, the
 *       members and the requests reference {@code trips(id) ON DELETE
 *       CASCADE}, so "deleting a trip deletes everything it put anywhere" is
 *       a property of the schema. The rendered public page is not in the
 *       database and is still removed by {@code TripService.delete}.</li>
 *   <li><b>A null column leaves the field at its initialiser</b>, as Jackson
 *       does with a key absent from the file — a trip with no stored display
 *       currency reads back as {@code "EUR"} in both stores.</li>
 * </ul>
 *
 * <h2>Where it differs from YAML, deliberately</h2>
 * <ul>
 *   <li><b>{@code Trip.exchangeRates} is not stored.</b> It has not been read
 *       since rates became one table for the install, and survives on the
 *       class only so that old YAML files still load. A database has no old
 *       files, so there is no column, and a trip always reads back with an
 *       empty map.</li>
 *   <li><b>Ties in {@link #findAllForUser}</b> (two trips starting the same
 *       day) fall back to {@code created_at, id}; YAML keeps the order trips
 *       were added to its index, which is the same thing for any trip created
 *       through the app.</li>
 *   <li><b>A member cannot be listed twice, nor a request id repeated</b>:
 *       both are primary keys. The app never produces either.</li>
 *   <li><b>Timestamps keep microseconds</b>, {@code timestamptz}'s precision;
 *       the two stamped here are truncated to match.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcTripRepository implements TripRepository {

    private static final String UPSERT_TRIP = """
            INSERT INTO trips (id, slug, title, start_date, end_date, owner_user_id, status,
                               display_currency, published_theme, published_at,
                               created_at, updated_at, created_by_user_id, updated_by_user_id)
            VALUES (:id, :slug, :title, :startDate, :endDate, :ownerUserId, :status,
                    :displayCurrency, :publishedTheme, :publishedAt,
                    :createdAt, :updatedAt, :createdByUserId, :updatedByUserId)
            ON CONFLICT (id) DO UPDATE SET
                slug               = EXCLUDED.slug,
                title              = EXCLUDED.title,
                start_date         = EXCLUDED.start_date,
                end_date           = EXCLUDED.end_date,
                owner_user_id      = EXCLUDED.owner_user_id,
                status             = EXCLUDED.status,
                display_currency   = EXCLUDED.display_currency,
                published_theme    = EXCLUDED.published_theme,
                published_at       = EXCLUDED.published_at,
                created_at         = EXCLUDED.created_at,
                updated_at         = EXCLUDED.updated_at,
                created_by_user_id = EXCLUDED.created_by_user_id,
                updated_by_user_id = EXCLUDED.updated_by_user_id
            """;

    private static final String INSERT_MEMBER = """
            INSERT INTO trip_members (trip_id, user_id, position, email, role,
                                      invited_by_user_id, invited_at)
            VALUES (:tripId, :userId, :position, :email, :role, :invitedByUserId, :invitedAt)
            """;

    private static final String INSERT_REQUEST = """
            INSERT INTO publish_requests (trip_id, id, position, requested_by_user_id, status,
                                          note, theme, requested_at, decided_at, decided_by_user_id)
            VALUES (:tripId, :id, :position, :requestedByUserId, :status,
                    :note, :theme, :requestedAt, :decidedAt, :decidedByUserId)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcTripRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<Trip> findAllForUser(String userId) {
        return transactions.execute(status -> withChildren(jdbc.sql("""
                        SELECT t.* FROM trips t
                        WHERE EXISTS (SELECT 1 FROM trip_members m
                                      WHERE m.trip_id = t.id AND m.user_id = :userId)
                        ORDER BY t.start_date DESC NULLS LAST, t.created_at, t.id
                        """)
                .param("userId", userId)
                .query((rs, n) -> mapTrip(rs))
                .list()));
    }

    @Override
    public Optional<Trip> findById(String tripId) {
        return findOne("id", tripId);
    }

    @Override
    public Optional<Trip> findBySlug(String slug) {
        return findOne("slug", slug);
    }

    @Override
    public boolean slugExists(String slug) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM trips WHERE slug = :slug)")
                .param("slug", slug)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Trip save(Trip trip) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        trip.setUpdatedAt(now);
        if (trip.getCreatedAt() == null) trip.setCreatedAt(now);

        transactions.executeWithoutResult(status -> {
            jdbc.sql(UPSERT_TRIP)
                    .param("id", trip.getId())
                    .param("slug", trip.getSlug())
                    .param("title", trip.getTitle())
                    .param("startDate", trip.getStartDate())
                    .param("endDate", trip.getEndDate())
                    .param("ownerUserId", trip.getOwnerUserId())
                    // NOT NULL DEFAULT columns: an explicit null is not "use
                    // the default", so a null field is given the initialiser.
                    .param("status", orDefault(trip.getStatus(), TripStatus.DRAFT))
                    .param("displayCurrency", trip.getDisplayCurrency())
                    .param("publishedTheme", trip.getPublishedTheme())
                    .param("publishedAt", JdbcValues.timestamptz(trip.getPublishedAt()))
                    .param("createdAt", JdbcValues.timestamptz(trip.getCreatedAt()))
                    .param("updatedAt", JdbcValues.timestamptz(trip.getUpdatedAt()))
                    .param("createdByUserId", trip.getCreatedByUserId())
                    .param("updatedByUserId", trip.getUpdatedByUserId())
                    .update();

            jdbc.sql("DELETE FROM trip_members WHERE trip_id = :tripId")
                    .param("tripId", trip.getId()).update();
            jdbc.sql("DELETE FROM publish_requests WHERE trip_id = :tripId")
                    .param("tripId", trip.getId()).update();

            List<TripMember> members = trip.getMembers();
            for (int position = 0; position < members.size(); position++) {
                TripMember member = members.get(position);
                jdbc.sql(INSERT_MEMBER)
                        .param("tripId", trip.getId())
                        .param("userId", member.getUserId())
                        .param("position", position)
                        .param("email", member.getEmail())
                        .param("role", orDefault(member.getRole(), TripRole.MEMBER))
                        .param("invitedByUserId", member.getInvitedByUserId())
                        .param("invitedAt", JdbcValues.timestamptz(member.getInvitedAt()))
                        .update();
            }

            List<PublishRequest> requests = trip.getPublishRequests();
            for (int position = 0; position < requests.size(); position++) {
                PublishRequest request = requests.get(position);
                jdbc.sql(INSERT_REQUEST)
                        .param("tripId", trip.getId())
                        .param("id", request.getId())
                        .param("position", position)
                        .param("requestedByUserId", request.getRequestedByUserId())
                        .param("status", orDefault(request.getStatus(), PublishRequest.Status.PENDING))
                        .param("note", request.getNote())
                        .param("theme", request.getTheme())
                        .param("requestedAt", JdbcValues.timestamptz(request.getRequestedAt()))
                        .param("decidedAt", JdbcValues.timestamptz(request.getDecidedAt()))
                        .param("decidedByUserId", request.getDecidedByUserId())
                        .update();
            }
        });
        return trip;
    }

    @Override
    public void delete(Trip trip) {
        jdbc.sql("DELETE FROM trips WHERE id = :id").param("id", trip.getId()).update();
    }

    // ---- reading -----------------------------------------------------------

    private Optional<Trip> findOne(String column, String value) {
        return transactions.execute(status -> withChildren(jdbc.sql("SELECT * FROM trips WHERE " + column + " = :value")
                .param("value", value)
                .query((rs, n) -> mapTrip(rs))
                .list())
                .stream()
                .findFirst());
    }

    /**
     * Attaches members and publish requests to the given trips: two queries
     * however many trips there are, rather than two per trip.
     */
    private List<Trip> withChildren(List<Trip> trips) {
        if (trips.isEmpty()) return trips;
        Map<String, Trip> byId = new HashMap<>();
        for (Trip trip : trips) byId.put(trip.getId(), trip);
        Object ids = JdbcValues.textArray(new ArrayList<>(byId.keySet()));

        jdbc.sql("SELECT * FROM trip_members WHERE trip_id = ANY(:ids) ORDER BY trip_id, position")
                .param("ids", ids)
                .query(rs -> {
                    byId.get(rs.getString("trip_id")).getMembers().add(mapMember(rs));
                });
        jdbc.sql("SELECT * FROM publish_requests WHERE trip_id = ANY(:ids) ORDER BY trip_id, position")
                .param("ids", ids)
                .query(rs -> {
                    byId.get(rs.getString("trip_id")).getPublishRequests().add(mapRequest(rs));
                });
        return trips;
    }

    private static Trip mapTrip(ResultSet rs) throws SQLException {
        Trip trip = new Trip();
        trip.setId(rs.getString("id"));
        trip.setSlug(rs.getString("slug"));
        trip.setTitle(rs.getString("title"));
        trip.setStartDate(JdbcValues.localDate(rs, "start_date"));
        trip.setEndDate(JdbcValues.localDate(rs, "end_date"));
        trip.setOwnerUserId(rs.getString("owner_user_id"));
        trip.setStatus(JdbcValues.enumValue(rs, "status", TripStatus.class, trip.getStatus()));
        String displayCurrency = rs.getString("display_currency");
        if (displayCurrency != null) trip.setDisplayCurrency(displayCurrency);
        String publishedTheme = rs.getString("published_theme");
        if (publishedTheme != null) trip.setPublishedTheme(publishedTheme);
        trip.setPublishedAt(JdbcValues.instant(rs, "published_at"));
        trip.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        trip.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        trip.setCreatedByUserId(rs.getString("created_by_user_id"));
        trip.setUpdatedByUserId(rs.getString("updated_by_user_id"));
        return trip;
    }

    private static TripMember mapMember(ResultSet rs) throws SQLException {
        TripMember member = new TripMember();
        member.setUserId(rs.getString("user_id"));
        member.setEmail(rs.getString("email"));
        member.setRole(JdbcValues.enumValue(rs, "role", TripRole.class, member.getRole()));
        member.setInvitedByUserId(rs.getString("invited_by_user_id"));
        member.setInvitedAt(JdbcValues.instant(rs, "invited_at"));
        return member;
    }

    private static PublishRequest mapRequest(ResultSet rs) throws SQLException {
        PublishRequest request = new PublishRequest();
        request.setId(rs.getString("id"));
        request.setRequestedByUserId(rs.getString("requested_by_user_id"));
        request.setStatus(JdbcValues.enumValue(rs, "status", PublishRequest.Status.class, request.getStatus()));
        request.setNote(rs.getString("note"));
        request.setTheme(rs.getString("theme"));
        request.setRequestedAt(JdbcValues.instant(rs, "requested_at"));
        request.setDecidedAt(JdbcValues.instant(rs, "decided_at"));
        request.setDecidedByUserId(rs.getString("decided_by_user_id"));
        return request;
    }

    private static String orDefault(Enum<?> value, Enum<?> fallback) {
        return JdbcValues.enumName(value == null ? fallback : value);
    }
}
