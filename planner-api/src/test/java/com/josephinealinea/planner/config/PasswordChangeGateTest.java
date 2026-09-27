package com.josephinealinea.planner.config;

import com.josephinealinea.planner.identity.api.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordChangeGateTest {

    private final FilterErrors errors = Mockito.mock(FilterErrors.class);

    private int run(String uri) throws Exception {
        CurrentUserContext user = new CurrentUserContext();
        user.set(new CurrentUser("u1", "a@b.c", "A", true, null));
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUserContext> provider = Mockito.mock(ObjectProvider.class);
        Mockito.when(provider.getObject()).thenReturn(user);
        Mockito.doAnswer(i -> {
            ((MockHttpServletResponse) i.getArgument(1)).setStatus(409);
            return null;
        }).when(errors).write(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
        PasswordChangeGate gate = new PasswordChangeGate(provider, errors);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        gate.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void aMemberWhoMustChangeTheirPasswordIsBlockedFromOrdinaryEndpoints() throws Exception {
        assertThat(run("/api/v1/trips")).isEqualTo(409);
    }

    @Test
    void thePublicRefreshPathIsNotAnswered409() throws Exception {
        assertThat(run("/api/v1/public/trips/x/flights")).isEqualTo(200);
    }
}
