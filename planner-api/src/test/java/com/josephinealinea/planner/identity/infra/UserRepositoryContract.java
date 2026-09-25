package com.josephinealinea.planner.identity.infra;

import com.josephinealinea.planner.identity.domain.PublishedPageSettings;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.storage.EveryField;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every behaviour {@link YamlUserRepository} has, as assertions any
 * {@link UserRepository} must pass. Run by {@code YamlUserRepositoryContractTest}
 * against a temp directory and by {@code JdbcUserRepositoryContractTest}
 * against PostgreSQL, so the two stores cannot drift apart unnoticed.
 *
 * Deliberately absent: the order {@code findAll} answers in (YAML: insertion,
 * PostgreSQL: {@code created_at, id}) — no caller depends on it; see
 * {@link JdbcUserRepository}.
 */
public abstract class UserRepositoryContract {

    protected abstract UserRepository repository();

    // ── every field ─────

    /**
     * A user with every field set to something other than its default, saved
     * and read back whole. {@link EveryField} makes a field added to User
     * later fail here until the fixture — and therefore the store — has it.
     */
    @Test
    void everyFieldSurvivesARoundTrip() {
        User user = everyFieldSet("u-1", "ana@example.com");
        EveryField.assertEverySet(user, User::new, Set.of());

        User saved = repository().save(user);

        assertThat(repository().findById("u-1")).get()
                .usingRecursiveComparison()
                .isEqualTo(saved);
    }

    @Test
    void primitiveSettingsReadBackAsFalseWhenNeverSet() {
        User user = user("u-1", "ana@example.com");

        repository().save(user);

        User read = repository().findById("u-1").orElseThrow();
        assertThat(read.isMustChangePassword()).isFalse();
        assertThat(read.getPublishedPage().isItineraryCost()).isFalse();
        assertThat(read.getCurrencies()).isEmpty();
        assertThat(read.getScreenName()).isNull();
    }

    @Test
    void currenciesKeepTheirOrder() {
        User user = user("u-1", "ana@example.com");
        user.setCurrencies(new ArrayList<>(List.of("SGD", "EUR", "PEN", "BOB", "USD")));

        repository().save(user);

        assertThat(repository().findById("u-1").orElseThrow().getCurrencies())
                .containsExactly("SGD", "EUR", "PEN", "BOB", "USD");
    }

    // ── emails ─────

    @Test
    void anEmailIsNormalisedWhenSaved() {
        User user = user("u-1", "  Ana.Lopez@Example.COM ");

        User saved = repository().save(user);

        assertThat(saved.getEmail()).isEqualTo("ana.lopez@example.com");
        assertThat(repository().findById("u-1").orElseThrow().getEmail())
                .isEqualTo("ana.lopez@example.com");
    }

    @Test
    void anEmailIsNormalisedWhenLookedUp() {
        repository().save(user("u-1", "ana@example.com"));

        assertThat(repository().findByEmail(" ANA@Example.com  ")).get()
                .extracting(User::getId).isEqualTo("u-1");
        assertThat(repository().findByEmail("someone@example.com")).isEmpty();
        assertThat(repository().findByEmail(null)).isEmpty();
    }

    // ── saving ─────

    @Test
    void savingAnExistingIdReplacesItRatherThanAddingASecond() {
        repository().save(user("u-1", "ana@example.com"));
        User renamed = repository().findById("u-1").orElseThrow();
        renamed.setScreenName("Ana");
        renamed.setEmail("ana.new@example.com");

        repository().save(renamed);

        assertThat(repository().findAll()).hasSize(1);
        User read = repository().findById("u-1").orElseThrow();
        assertThat(read.getScreenName()).isEqualTo("Ana");
        assertThat(repository().findByEmail("ana@example.com")).isEmpty();
        assertThat(repository().findByEmail("ana.new@example.com")).isPresent();
    }

    @Test
    void saveStampsUpdatedAtEveryTimeAndCreatedAtOnce() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        User user = user("u-1", "ana@example.com");

        repository().save(user);
        User first = repository().findById("u-1").orElseThrow();
        assertThat(first.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.minusMillis(1));
        assertThat(first.getUpdatedAt()).isNotNull();

        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        first.setUpdatedAt(old);
        repository().save(first);

        User second = repository().findById("u-1").orElseThrow();
        assertThat(second.getCreatedAt()).as("created once").isEqualTo(first.getCreatedAt());
        assertThat(second.getUpdatedAt()).as("stamped on every save").isAfter(old);
    }

    @Test
    void aCreatedAtAlreadySetIsKept() {
        User user = user("u-1", "ana@example.com");
        Instant created = Instant.parse("2025-03-01T08:00:00Z");
        user.setCreatedAt(created);

        repository().save(user);

        assertThat(repository().findById("u-1").orElseThrow().getCreatedAt()).isEqualTo(created);
    }

    // ── finding ─────

    @Test
    void isEmptyUntilTheFirstUserIsSaved() {
        assertThat(repository().isEmpty()).isTrue();
        assertThat(repository().findAll()).isEmpty();

        repository().save(user("u-1", "ana@example.com"));

        assertThat(repository().isEmpty()).isFalse();
    }

    @Test
    void findAllListsEveryUser() {
        repository().save(user("u-1", "ana@example.com"));
        repository().save(user("u-2", "ben@example.com"));
        repository().save(user("u-3", "cai@example.com"));

        assertThat(repository().findAll()).extracting(User::getId)
                .containsExactlyInAnyOrder("u-1", "u-2", "u-3");
    }

    @Test
    void findAllByIdAnswersOnlyTheIdsAskedForThatExist() {
        repository().save(user("u-1", "ana@example.com"));
        repository().save(user("u-2", "ben@example.com"));
        repository().save(user("u-3", "cai@example.com"));

        assertThat(repository().findAllById(List.of("u-3", "u-1", "nobody")))
                .extracting(User::getId)
                .containsExactlyInAnyOrder("u-1", "u-3");
        assertThat(repository().findAllById(List.of())).isEmpty();
    }

    @Test
    void anUnknownIdIsEmpty() {
        assertThat(repository().findById("nobody")).isEmpty();
    }

    // ── fixtures ─────

    protected static User user(String id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    /** Every field away from its default, with instants at microsecond precision. */
    protected static User everyFieldSet(String id, String email) {
        User user = user(id, email);
        user.setScreenName("Ana");
        user.setHomeCountryCode("PE");
        user.setLanguageCode("xx");
        user.setTierLevel(com.josephinealinea.planner.identity.domain.TierLevel.PRO);
        user.setPasswordHash("{bcrypt}$2a$10$abcdefghijklmnopqrstuv");
        user.setMustChangePassword(true);
        user.setCurrencies(new ArrayList<>(List.of("SGD", "EUR", "PEN")));
        user.setDisplayCurrency("SGD");
        user.setPublishedPage(new PublishedPageSettings(true, true, true, true, true));
        user.setCreatedAt(Instant.parse("2026-01-02T03:04:05.123456Z"));
        user.setUpdatedAt(Instant.parse("2026-01-03T03:04:05.654321Z"));
        return user;
    }
}
