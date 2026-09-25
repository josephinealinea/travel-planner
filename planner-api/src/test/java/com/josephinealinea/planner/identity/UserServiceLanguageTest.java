package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The language a member reads the app in is theirs to set, and only to one we can speak. */
class UserServiceLanguageTest {

    private static final Set<String> SUPPORTED = Set.of("en", "xx");

    private UserService users;
    private String id;

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
                new AppProperties.Currencies(List.of("EUR", "USD"), "EUR", null));
        users = new UserService(new YamlUserRepository(new YamlStore(), new YamlPaths(props), new TripLocks()),
                new BCryptPasswordEncoder(), props);
        id = users.findOrCreate("sam@example.com").user().getId();
    }

    @Test
    void aNewAccountHasNoLanguageChosen() {
        assertThat(users.require(id).getLanguageCode()).isNull();
    }

    @Test
    void aSupportedLanguageIsStored() {
        users.updateLanguage(id, "xx", SUPPORTED);
        assertThat(users.require(id).getLanguageCode()).isEqualTo("xx");
    }

    @Test
    void theCodeIsNormalisedToLowercase() {
        users.updateLanguage(id, " XX ", SUPPORTED);
        assertThat(users.require(id).getLanguageCode()).isEqualTo("xx");
    }

    @Test
    void blankClearsTheChoice() {
        users.updateLanguage(id, "xx", SUPPORTED);
        User cleared = users.updateLanguage(id, "  ", SUPPORTED);
        assertThat(cleared.getLanguageCode()).isNull();
        assertThat(users.require(id).getLanguageCode()).isNull();
    }

    @Test
    void aLanguageWeDoNotHaveIsRefusedAndNothingChanges() {
        users.updateLanguage(id, "xx", SUPPORTED);
        assertThatThrownBy(() -> users.updateLanguage(id, "fr", SUPPORTED))
                .isInstanceOf(ApiException.class)
                .hasMessage("error.language.unsupported");
        assertThat(users.require(id).getLanguageCode()).isEqualTo("xx");
    }
}
