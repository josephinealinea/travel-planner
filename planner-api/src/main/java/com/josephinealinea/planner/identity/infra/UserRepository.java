package com.josephinealinea.planner.identity.infra;

import com.josephinealinea.planner.identity.domain.User;

import java.util.List;
import java.util.Optional;

/**
 * The storage contract for users. A JPA implementation slots in behind
 * feature-enable-database=true without touching any service.
 */
public interface UserRepository {

    List<User> findAll();

    Optional<User> findById(String id);

    Optional<User> findByEmail(String email);

    List<User> findAllById(List<String> ids);

    User save(User user);

    boolean isEmpty();
}
