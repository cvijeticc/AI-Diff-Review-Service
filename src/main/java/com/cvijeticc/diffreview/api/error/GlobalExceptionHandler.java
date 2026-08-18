package com.cvijeticc.diffreview.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Every non-2xx response uses the error envelope from the contract. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Object> handleRateLimited(RateLimitedException e) {
        return ResponseEntity.status(e.status())
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(ErrorEnvelope.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(ErrorEnvelope.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404).body(ErrorEnvelope.of("not_found", "No such endpoint"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(ErrorEnvelope.of("method_not_allowed", e.getMessage()));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<Object> handleAny(Throwable e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(500).body(ErrorEnvelope.of("internal", "Internal server error"));
    }
}
