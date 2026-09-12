package com.josephinealinea.planner.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders every failure as a ProblemDetail carrying a machine-readable "code"
 * and, on validation errors, a "errors" field->message map. This is the shape
 * the frontend's ApiError already parses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail onApiException(ApiException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Some fields need attention");
        problem.setProperty("code", "validation_failed");
        problem.setProperty("errors", errors);
        return problem;
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
                HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side.");
        problem.setProperty("code", "internal_error");
        return problem;
    }
}
