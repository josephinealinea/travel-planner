package com.josephinealinea.planner.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.i18n.I18nConfig;
import com.josephinealinea.planner.i18n.Messages;
import com.josephinealinea.planner.i18n.RequestLocale;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterErrorsTest {

    private final Messages messages = I18nConfig.standalone();
    private final FilterErrors errors = new FilterErrors(
            messages, new RequestLocale(messages, null), new ObjectMapper());

    private JsonNode write(String acceptLanguage, String key, Object... args) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/x");
        if (acceptLanguage != null) request.addHeader("Accept-Language", acceptLanguage);
        var response = new MockHttpServletResponse();
        errors.write(request, response, HttpStatus.FORBIDDEN, "csrf_failed", key, args);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/problem+json"));
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    @Test
    void writesTheSameShapeTheHandlerDoesInTheRequestsLanguage() throws Exception {
        JsonNode body = write("xx", "test.plain");
        assertEquals(403, body.get("status").asInt());
        assertEquals("csrf_failed", body.get("code").asText());
        assertEquals("[xx] Plain text", body.get("detail").asText());
    }

    @Test
    void englishWhenNothingElseIsAsked() throws Exception {
        assertEquals("Plain text", write(null, "test.plain").get("detail").asText());
    }

    @Test
    void aQuoteInAMessageDoesNotBreakTheJson() throws Exception {
        assertEquals("Hello \"Bob\" \\ {x}, you have 2 trips",
                write(null, "test.args", "\"Bob\" \\ {x}", 2).get("detail").asText());
    }
}
