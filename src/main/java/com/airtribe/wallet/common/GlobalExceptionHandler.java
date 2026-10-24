package com.airtribe.wallet.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorBodies errors;

    public GlobalExceptionHandler(ErrorBodies errors) {
        this.errors = errors;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> api(ApiException e) {
        return errors.of(e).toResponse();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return errors.of(400, "VALIDATION_ERROR", message).toResponse();
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<String> unreadable(Exception e) {
        return errors.of(400, "BAD_REQUEST", "Malformed request: " + rootMessage(e)).toResponse();
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<String> noRoute(NoResourceFoundException e) {
        return errors.of(404, "NOT_FOUND", "No such endpoint").toResponse();
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<String> method(HttpRequestMethodNotSupportedException e) {
        return errors.of(405, "METHOD_NOT_ALLOWED", e.getMessage()).toResponse();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return errors.of(500, "INTERNAL_ERROR", "Something went wrong; it is safe to retry with the same Idempotency-Key")
                .toResponse();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.split("\n")[0];
    }
}
