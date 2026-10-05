package com.qe.agent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;

/**
 * GlobalExceptionHandler — catches all unhandled exceptions and returns
 * RFC 7807 {@link ProblemDetail} responses instead of leaking stack traces.
 *
 * <p>Rules:
 * <ul>
 *   <li>4xx errors (validation, bad input): logged at WARN, detail included.</li>
 *   <li>5xx errors (unexpected): logged at ERROR with full stack trace,
 *       but the response body contains only a generic message — no internal
 *       detail is exposed to the caller.</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
@SuppressWarnings("null")
public class GlobalExceptionHandler {

    private static final URI VALIDATION_TYPE = Objects.requireNonNull(URI.create("about:blank"));

    // -------------------------------------------------------------------------
    // 400 — Validation failures from @Valid
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(VALIDATION_TYPE);
        problem.setTitle("Validation Failed");

        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request body");

        problem.setDetail(detail);
        problem.setProperty("timestamp", Instant.now().toString());

        log.warn("[GlobalExceptionHandler] Validation error: {}", detail);
        return ResponseEntity.badRequest().body(problem);
    }

    // -------------------------------------------------------------------------
    // 400 — Missing required request parameters
    // -------------------------------------------------------------------------

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> handleMissingParam(MissingServletRequestParameterException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Missing Request Parameter");
        problem.setDetail("Required parameter '" + ex.getParameterName() + "' is missing");
        problem.setProperty("timestamp", Instant.now().toString());

        log.warn("[GlobalExceptionHandler] Missing parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest().body(problem);
    }

    // -------------------------------------------------------------------------
    // 400 — Type mismatch (e.g. non-numeric string for numeric param)
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Type Mismatch");
        Class<?> requiredType = ex.getRequiredType();
        problem.setDetail("Parameter '" + ex.getName() + "' must be of type "
                + (requiredType != null ? requiredType.getSimpleName() : "unknown"));
        problem.setProperty("timestamp", Instant.now().toString());

        log.warn("[GlobalExceptionHandler] Type mismatch on param '{}': {}", ex.getName(), ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    // -------------------------------------------------------------------------
    // 400 — Illegal argument or path validation failures
    // -------------------------------------------------------------------------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad Request");
        problem.setDetail(ex.getMessage() != null ? ex.getMessage() : "Invalid argument");
        problem.setProperty("timestamp", Instant.now().toString());

        log.warn("[GlobalExceptionHandler] Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    // -------------------------------------------------------------------------
    // 403 — Security violations
    // -------------------------------------------------------------------------

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ProblemDetail> handleSecurityException(SecurityException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access Denied");
        problem.setDetail(ex.getMessage() != null ? ex.getMessage() : "Access denied");
        problem.setProperty("timestamp", Instant.now().toString());

        log.warn("[GlobalExceptionHandler] Security violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    // -------------------------------------------------------------------------
    // 500 — Catch-all: never expose internal detail to the caller
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        // Log full stack trace server-side for diagnosis
        log.error("[GlobalExceptionHandler] Unhandled exception: {}", ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        // Generic message only — no stack traces, class names, or internal paths exposed
        problem.setDetail("An unexpected error occurred. Please check server logs for details.");
        problem.setProperty("timestamp", Instant.now().toString());

        return ResponseEntity.internalServerError().body(problem);
    }
}
