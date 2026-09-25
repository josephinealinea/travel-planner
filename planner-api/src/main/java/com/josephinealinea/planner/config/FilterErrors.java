package com.josephinealinea.planner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.josephinealinea.planner.i18n.Messages;
import com.josephinealinea.planner.i18n.RequestLocale;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Errors written by filters, which run before Spring MVC and so never reach
 * {@code GlobalExceptionHandler}. Same shape as the handler's, same message
 * files, same language rule.
 */
@Component
public class FilterErrors {

    private final Messages messages;
    private final RequestLocale locale;
    private final ObjectMapper json;

    public FilterErrors(Messages messages, RequestLocale locale, ObjectMapper json) {
        this.messages = messages;
        this.locale = locale;
        this.json = json;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String code, String messageKey, Object... args) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", messages.get(locale.of(request), messageKey, args));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(body));
    }
}
