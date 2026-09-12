package com.josephinealinea.planner.identity.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.josephinealinea.planner.identity.domain.User;
import com.josephinealinea.planner.storage.TripLocks;
import com.josephinealinea.planner.storage.YamlPaths;
import com.josephinealinea.planner.storage.YamlStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** All users live in a single data/users.yml — there will never be many. */
@Repository
@ConditionalOnProperty(name = "feature-enable-database", havingValue = "false", matchIfMissing = true)
public class YamlUserRepository implements UserRepository {

    private static final TypeReference<List<User>> USER_LIST = new TypeReference<>() {};

    private final YamlStore store;
    private final YamlPaths paths;
    private final TripLocks locks;

    public YamlUserRepository(YamlStore store, YamlPaths paths, TripLocks locks) {
        this.store = store;
        this.paths = paths;
        this.locks = locks;
    }

    @Override
    public List<User> findAll() {
        return locks.read(TripLocks.USERS, this::load);
    }

    @Override
    public Optional<User> findById(String id) {
        return findAll().stream().filter(u -> u.getId().equals(id)).findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String normalised = normalise(email);
        return findAll().stream().filter(u -> u.getEmail().equals(normalised)).findFirst();
    }

    @Override
    public List<User> findAllById(List<String> ids) {
        return findAll().stream().filter(u -> ids.contains(u.getId())).toList();
    }

    @Override
    public User save(User user) {
        return locks.write(TripLocks.USERS, () -> {
            List<User> users = load();
            user.setEmail(normalise(user.getEmail()));
            user.setUpdatedAt(Instant.now());
            if (user.getCreatedAt() == null) user.setCreatedAt(Instant.now());

            int existing = -1;
            for (int i = 0; i < users.size(); i++) {
                if (users.get(i).getId().equals(user.getId())) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) users.set(existing, user);
            else users.add(user);

            store.write(paths.users(), users);
            return user;
        });
    }

    @Override
    public boolean isEmpty() {
        return findAll().isEmpty();
    }

    private List<User> load() {
        return store.readList(paths.users(), USER_LIST);
    }

    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
