package com.bridgepay.creditbureau.web;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleMalformedPathVariable(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR",
                ex.getName() + " must be a valid UUID"));
    }

    private ApiError error(String code, String message) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        return new ApiError(code, message, traceId, Instant.now());
    }
}
