package com.josephinealinea.planner.identity;

import com.josephinealinea.planner.config.AppProperties;
import com.josephinealinea.planner.identity.api.UserService;
import com.josephinealinea.planner.identity.domain.TierLevel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Trip-page settings: masking is for everyone, whole-trip only for Rockstar accounts. */
class UserServiceTripPagesTest {

    private UserService users;
    private YamlUserRepository repository;
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
        repository = new YamlUserRepository(new YamlStore(), new YamlPaths(props), new TripLocks());
        users = new UserService(repository,
                new BCryptPasswordEncoder(), props);
        id = users.findOrCreate("sam@example.com").user().getId();
    }

    @Test
    void bothFlagsStartOff() {
        var settings = users.require(id).getTripPages();
        assertThat(settings.isShowWholeTrip()).isFalse();
        assertThat(settings.isMaskAmounts()).isFalse();
    }

    @Test
    void anyAccountCanTurnMaskingOnAndOneFlagLeavesTheOtherAlone() {
        User updated = users.updateTripPageSettings(id, null, true);
        assertThat(updated.getTripPages().isMaskAmounts()).isTrue();
        assertThat(updated.getTripPages().isShowWholeTrip()).isFalse();
    }

    @Test
    void aNonRockstarAccountCannotTurnShowWholeTripOn() {
        assertThatThrownBy(() -> users.updateTripPageSettings(id, true, null))
                .isInstanceOf(ApiException.class);
        assertThat(users.require(id).getTripPages().isShowWholeTrip()).isFalse();
    }

    @Test
    void aRockstarAccountCan() {
        User user = users.require(id);
        user.setTierLevel(TierLevel.ROCKSTAR);
        repository.save(user);
        assertThat(users.updateTripPageSettings(id, true, null).getTripPages().isShowWholeTrip()).isTrue();
    }
}
