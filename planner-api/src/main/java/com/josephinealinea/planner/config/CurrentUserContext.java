package com.josephinealinea.planner.config;

import com.josephinealinea.planner.identity.api.CurrentUser;
import com.josephinealinea.planner.shared.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * Holds the authenticated user for the duration of one request. Request-scoped
 * rather than a ThreadLocal so Spring handles the cleanup.
 */
@Component
@RequestScope
public class CurrentUserContext {

    private CurrentUser user;

    public void set(CurrentUser user) {
        this.user = user;
    }

    public CurrentUser get() {
        if (user == null) throw ApiException.unauthorized("error.signIn.required");
        return user;
    }

    public String userId() {
        return get().id();
    }

    public boolean isAuthenticated() {
        return user != null;
    }
}
