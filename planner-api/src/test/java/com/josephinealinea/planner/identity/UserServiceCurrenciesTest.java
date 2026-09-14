package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.ApiException;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A brand-new user is seeded with app.currencies.defaults; after that the
 * list is theirs to replace, independent of the trip they happen to be on.
 */
class UserServiceCurrenciesTest {

    private UserService users;
    private YamlUserRepository repository;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AppProperties props = new AppProperties(
                new AppProperties.Storage(dir.toString()),
                new AppProperties.Publish(null, null),
                new AppProperties.Mail(null, null),
                new AppProperties.Security(null, null, false),
                new AppProperties.Cors(null),
                new AppProperties.Geocoding(null, null, 0, 0),
                new AppProperties.Weather(null, null, null, null, 0, 0, null),
                new AppProperties.Rates(null, null, "0 0 0 * * *", null),
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Currencies(List.of("EUR", "USD", "SGD"), null, null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        repository = new YamlUserRepository(store, paths, new TripLocks());
        users = new UserService(repository, new BCryptPasswordEncoder(), props);
    }

    @Test
    void aNewUserIsSeededWithTheConfiguredDefaults() {
        var invited = users.findOrCreate("sam@example.com");

        assertThat(invited.created()).isTrue();
        assertThat(invited.user().getCurrencies()).containsExactly("EUR", "USD", "SGD");
    }

    @Test
    void anExistingUserIsNotReSeeded() {
        users.findOrCreate("sam@example.com");
        users.updateCurrencies(
                repository.findByEmail("sam@example.com").orElseThrow().getId(), List.of("GBP"));

        var invited = users.findOrCreate("sam@example.com");

        assertThat(invited.created()).isFalse();
        assertThat(invited.user().getCurrencies()).containsExactly("GBP");
    }

    @Test
    void updateCurrenciesReplacesUppercasesAndDedupes() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        var updated = users.updateCurrencies(id, List.of("gbp", "GBP", "chf"));

        assertThat(updated.getCurrencies()).containsExactly("GBP", "CHF");
        assertThat(repository.findById(id).orElseThrow().getCurrencies())
                .containsExactly("GBP", "CHF");
    }

    @Test
    void updateCurrenciesRejectsAnEmptyList() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateCurrencies(id, List.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void updateCurrenciesRejectsAMalformedCode() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateCurrencies(id, List.of("EU")))
                .isInstanceOf(ApiException.class);
    }
}
