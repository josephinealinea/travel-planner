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
 * A user's display currency — the one their budget totals show in, on every
 * trip — is seeded from app.currencies.defaultDisplay and is otherwise
 * independent of the currencies list (what "record a cost" forms offer) and
 * of any trip's own displayCurrency (its exchange-rate anchor).
 */
class UserServiceDisplayCurrencyTest {

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
                new AppProperties.Currencies(List.of("EUR", "USD", "SGD"), "EUR", null));

        YamlStore store = new YamlStore();
        YamlPaths paths = new YamlPaths(props);
        repository = new YamlUserRepository(store, paths, new TripLocks());
        users = new UserService(repository, new BCryptPasswordEncoder(), props);
    }

    @Test
    void aNewUserIsSeededWithTheConfiguredDefaultDisplayCurrency() {
        var invited = users.findOrCreate("sam@example.com");

        assertThat(invited.user().getDisplayCurrency()).isEqualTo("EUR");
    }

    @Test
    void updateDisplayCurrencyNormalisesAndUppercases() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        var updated = users.updateDisplayCurrency(id, "gbp");

        assertThat(updated.getDisplayCurrency()).isEqualTo("GBP");
        assertThat(repository.findById(id).orElseThrow().getDisplayCurrency()).isEqualTo("GBP");
    }

    @Test
    void updateDisplayCurrencyRejectsAMalformedCode() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateDisplayCurrency(id, "EU"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void updateDisplayCurrencyRejectsNull() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        assertThatThrownBy(() -> users.updateDisplayCurrency(id, null))
                .isInstanceOf(ApiException.class);
    }

    /**
     * The two are stored separately and validated separately — changing one
     * never has to touch the other, and a display currency need not appear in
     * the working list at all.
     */
    @Test
    void isIndependentOfTheWorkingCurrenciesList() {
        String id = users.findOrCreate("sam@example.com").user().getId();

        users.updateCurrencies(id, List.of("USD"));
        var updated = users.updateDisplayCurrency(id, "CHF");

        assertThat(updated.getDisplayCurrency()).isEqualTo("CHF");
        assertThat(updated.getCurrencies()).containsExactly("USD");
    }
}
