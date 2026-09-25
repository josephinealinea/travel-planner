package com.josephinealinea.planner.shared;

import com.josephinealinea.planner.i18n.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.NoSuchMessageException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders every failure as a ProblemDetail carrying a machine-readable "code"
 * and, on validation errors, a "errors" field->message map. This is the shape
 * the frontend's ApiError already parses. The words are resolved here, in the
 * request's language, from message keys.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A bean-validation message written as {@code {some.key}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\{([a-z0-9]+(?:\\.[a-z0-9]+)+)}$");

    private final Messages messages;

    public GlobalExceptionHandler(Messages messages) {
        this.messages = messages;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail onApiException(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), resolve(e));
        problem.setProperty("code", e.code());
        return problem;
    }

    /**
     * A key with no message must never turn a 400 into a 500: say what we have
     * and log it loudly. MessageKeysTest is what stops it getting this far.
     */
    private String resolve(ApiException e) {
        try {
            return messages.get(e.messageKey(), e.args());
        } catch (NoSuchMessageException missing) {
            log.error("No message for key '{}'", e.messageKey());
            return e.messageKey();
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fieldMessage(fe.getDefaultMessage())));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, messages.get("error.validation.failed"));
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    private String fieldMessage(String declared) {
        if (declared == null) return "";
        Matcher m = PLACEHOLDER.matcher(declared);
        if (!m.matches()) return declared;
        try {
            return messages.get(m.group(1));
        } catch (NoSuchMessageException missing) {
            log.error("No message for validation key '{}'", m.group(1));
            return declared;
        }
    }

    /**
     * Spring's own exceptions already carry the right status and body — a
     * missing path, an unsupported method, an unreadable payload. Rethrowing
     * lets its handling stand instead of relabelling all of them 500.
     */
    @ExceptionHandler(ErrorResponseException.class)
    ProblemDetail onErrorResponse(ErrorResponseException e) {
        return e.getBody();
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, messages.get("error.internal"));
        problem.setProperty("code", "internal_error");
        return problem;
    }
}
