package com.josephinealinea.planner.identity.infra;

import com.josephinealinea.planner.identity.domain.PublishedPageSettings;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.storage.jdbc.JdbcValues;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Users in PostgreSQL, one row each in {@code users}. Behaves as
 * {@link YamlUserRepository} does, and the contract test runs the same
 * assertions against both:
 *
 * <ul>
 *   <li><b>Emails are normalised here, in the same two places</b> — on
 *       {@link #save} and on {@link #findByEmail} — with the YAML class's own
 *       {@link YamlUserRepository#normalise}, so the two stores cannot drift
 *       on what "the same address" means. Services already call that method
 *       directly, so it stays the one definition.</li>
 *   <li><b>{@link #save} stamps {@code updatedAt} every time and
 *       {@code createdAt} once</b>, then inserts or replaces by id.</li>
 *   <li><b>{@code currencies} is an ordered {@code text[]}</b>: the order a
 *       member arranged their currencies in is the order the forms offer.</li>
 *   <li><b>{@code published_page} is one {@code jsonb} document</b>, the same
 *       shape the YAML store writes as a nested mapping, so the next
 *       published-page setting is a key rather than a column.</li>
 *   <li><b>A null column leaves the field at its initialiser</b>, which is
 *       what Jackson does with a key absent from {@code users.yml} — so
 *       nothing reads differently depending on the store.</li>
 * </ul>
 *
 * <h2>Where it differs from YAML, deliberately</h2>
 * <ul>
 *   <li><b>Order.</b> The YAML list is in insertion order; this table has no
 *       insertion-order column, so {@link #findAll} and {@link #findAllById}
 *       order by {@code created_at, id} — the same order for every user saved
 *       through the app, since {@code createdAt} is stamped on first save.
 *       Every caller collects the result into a map by id, so nothing
 *       depends on it.</li>
 *   <li><b>Two users cannot share an email.</b> {@code users.email} is
 *       {@code UNIQUE}; the YAML file would accept a duplicate, and
 *       {@code findByEmail} would then answer with the first. The app never
 *       creates one — {@code UserService.findOrCreate} looks the address up
 *       first — so the constraint only turns a latent bug into a loud one.</li>
 *   <li><b>Timestamps keep microseconds, not nanoseconds</b> — that is
 *       {@code timestamptz}'s precision. The stamps this class sets are
 *       truncated to match, so the object {@link #save} returns equals the
 *       one a later read gives back.</li>
 * </ul>
 */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "true")
public class JdbcUserRepository implements UserRepository {

    private static final String UPSERT = """
            INSERT INTO users (id, email, screen_name, home_country, password_hash, must_change_password,
                               currencies, display_currency, published_page,
                               created_at, updated_at)
            VALUES (:id, :email, :screenName, :homeCountry, :passwordHash, :mustChangePassword,
                    :currencies, :displayCurrency, :publishedPage::jsonb,
                    :createdAt, :updatedAt)
            ON CONFLICT (id) DO UPDATE SET
                email                = EXCLUDED.email,
                screen_name          = EXCLUDED.screen_name,
                home_country         = EXCLUDED.home_country,
                password_hash        = EXCLUDED.password_hash,
                must_change_password = EXCLUDED.must_change_password,
                currencies           = EXCLUDED.currencies,
                display_currency     = EXCLUDED.display_currency,
                published_page       = EXCLUDED.published_page,
                created_at           = EXCLUDED.created_at,
                updated_at           = EXCLUDED.updated_at
            """;

    private static final String ORDER = " ORDER BY created_at, id";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcUserRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<User> findAll() {
        return jdbc.sql("SELECT * FROM users" + ORDER)
                .query((rs, n) -> mapRow(rs))
                .list();
    }

    @Override
    public Optional<User> findById(String id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> mapRow(rs))
                .optional();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String normalised = YamlUserRepository.normalise(email);
        if (normalised == null) return Optional.empty();
        return jdbc.sql("SELECT * FROM users WHERE email = :email")
                .param("email", normalised)
                .query((rs, n) -> mapRow(rs))
                .optional();
    }

    @Override
    public List<User> findAllById(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM users WHERE id = ANY(:ids)" + ORDER)
                .param("ids", JdbcValues.textArray(ids))
                .query((rs, n) -> mapRow(rs))
                .list();
    }

    @Override
    public User save(User user) {
        user.setEmail(YamlUserRepository.normalise(user.getEmail()));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        user.setUpdatedAt(now);
        if (user.getCreatedAt() == null) user.setCreatedAt(now);

        transactions.executeWithoutResult(status -> jdbc.sql(UPSERT)
                .param("id", user.getId())
                .param("email", user.getEmail())
                .param("screenName", user.getScreenName())
                .param("homeCountry", user.getHomeCountry())
                .param("passwordHash", user.getPasswordHash())
                .param("mustChangePassword", user.isMustChangePassword())
                .param("currencies", JdbcValues.textArray(user.getCurrencies()))
                .param("displayCurrency", user.getDisplayCurrency())
                .param("publishedPage", JdbcValues.json(user.getPublishedPage()))
                .param("createdAt", JdbcValues.timestamptz(user.getCreatedAt()))
                .param("updatedAt", JdbcValues.timestamptz(user.getUpdatedAt()))
                .update());
        return user;
    }

    @Override
    public boolean isEmpty() {
        return !jdbc.sql("SELECT EXISTS (SELECT 1 FROM users)").query(Boolean.class).single();
    }

    private static User mapRow(ResultSet rs) throws SQLException {
        User user = new User();
        user.setId(rs.getString("id"));
        user.setEmail(rs.getString("email"));
        user.setScreenName(rs.getString("screen_name"));
        user.setHomeCountry(rs.getString("home_country"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setMustChangePassword(rs.getBoolean("must_change_password"));
        user.setCurrencies(JdbcValues.textList(rs, "currencies"));
        user.setDisplayCurrency(rs.getString("display_currency"));
        user.setPublishedPage(JdbcValues.json(rs, "published_page", PublishedPageSettings.class));
        user.setCreatedAt(JdbcValues.instant(rs, "created_at"));
        user.setUpdatedAt(JdbcValues.instant(rs, "updated_at"));
        return user;
    }
}
