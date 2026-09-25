package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.Emails;
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

/** Changing an email, taking it out of the system, and how long ones are shown. */
class UserEmailTest {

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
                new AppProperties.Currencies(List.of("EUR"), "EUR", null));
        repository = new YamlUserRepository(new YamlStore(), new YamlPaths(props), new TripLocks());
        users = new UserService(repository, new BCryptPasswordEncoder(), props);
    }

    @Test
    void theEmailCanBeChangedAndIsNormalised() {
        User sam = users.findOrCreate("sam@example.com").user();

        users.updateProfile(sam.getId(), null, null, "  New.Sam@Example.com ");

        assertThat(repository.findById(sam.getId()).orElseThrow().getEmail()).isEqualTo("new.sam@example.com");
    }

    @Test
    void anEmailAnotherAccountUsesIsRefused() {
        User sam = users.findOrCreate("sam@example.com").user();
        users.findOrCreate("alex@example.com");

        assertThatThrownBy(() -> users.updateProfile(sam.getId(), null, null, "alex@example.com"))
                .hasMessage("error.email.taken");
    }

    @Test
    void notAnEmailIsRefused() {
        User sam = users.findOrCreate("sam@example.com").user();

        assertThatThrownBy(() -> users.updateProfile(sam.getId(), null, null, "nope"))
                .hasMessage("error.email.invalid");
    }

    @Test
    void removingTheEmailKeepsTheAccountUnderAPlaceholder() {
        User sam = users.findOrCreate("sam@example.com").user();

        users.deactivateEmail(sam.getId());

        User after = repository.findById(sam.getId()).orElseThrow();
        assertThat(after.getEmail()).isEqualTo("deactivated-" + sam.getId() + "@example.com");
        assertThat(repository.findByEmail("sam@example.com")).isEmpty();
    }

    @Test
    void longAddressesAreCutAtThirtySevenCharactersWithAnEllipsis() {
        String deactivated = "deactivated-72e5d878-9721-4278-ba5a-55e8e391dc56@example.com";

        assertThat(Emails.shorten(deactivated)).isEqualTo(deactivated.substring(0, 37) + "…");
        assertThat(Emails.shorten(deactivated)).hasSize(38);
        assertThat(Emails.shorten("sam@example.com")).isEqualTo("sam@example.com");
    }
}
