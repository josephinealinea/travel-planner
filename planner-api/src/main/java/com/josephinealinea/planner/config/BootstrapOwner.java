package com.josephinealinea.planner.config;

import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.identity.infra.UserRepository;
import com.josephinealinea.planner.identity.infra.YamlUserRepository;
import com.josephinealinea.planner.shared.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;

/**
 * There is no self-signup — accounts only come into being when someone adds you
 * to a trip. That leaves a chicken-and-egg problem on a fresh install, so the
 * first account is seeded from configuration.
 */
@Configuration
public class BootstrapOwner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapOwner.class);

    @Bean
    ApplicationRunner seedFirstUser(AppProperties props, UserRepository users, PasswordEncoder encoder) {
        return args -> {
            String email = props.bootstrap() == null ? null : props.bootstrap().ownerEmail();
            String password = props.bootstrap() == null ? null : props.bootstrap().ownerPassword();

            if (email == null || email.isBlank()) {
                if (users.isEmpty()) {
                    log.warn("No users exist and app.bootstrap.owner-email is unset — "
                            + "nobody can sign in. Set app.bootstrap.owner-email and "
                            + "app.bootstrap.owner-password, then restart.");
                }
                return;
            }

            String normalised = YamlUserRepository.normalise(email);
            if (users.findByEmail(normalised).isPresent()) {
                log.info("Bootstrap owner {} already exists — leaving it alone.", normalised);
                return;
            }

            String plaintext = (password == null || password.isBlank()) ? Ids.defaultPassword() : password;
            User owner = new User();
            owner.setId(Ids.newId());
            owner.setEmail(normalised);
            owner.setPasswordHash(encoder.encode(plaintext));
            owner.setCurrencies(new ArrayList<>(props.currencies().defaults()));
            // A configured password is deliberate, so do not force a change;
            // a generated one must be replaced on first sign-in.
            owner.setMustChangePassword(password == null || password.isBlank());
            users.save(owner);

            if (password == null || password.isBlank()) {
                log.warn("Created bootstrap owner {} with generated password: {} "
                        + "(you will be asked to change it at first sign-in)", normalised, plaintext);
            } else {
                log.info("Created bootstrap owner {}", normalised);
            }
        };
    }
}
