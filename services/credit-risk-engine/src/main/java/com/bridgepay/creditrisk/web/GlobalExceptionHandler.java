package com.bridgepay.creditrisk.web;

import com.bridgepay.creditrisk.client.BureauUnavailableException;
import com.bridgepay.creditrisk.client.RepaymentHistoryUnavailableException;
import com.bridgepay.creditrisk.scoring.ModelUnavailableException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", message));
    }

    /** Only creditLimit lets these escape - score() catches everything and falls back to MANUAL_REVIEW. */
    @ExceptionHandler({BureauUnavailableException.class, RepaymentHistoryUnavailableException.class,
            ModelUnavailableException.class})
    public ResponseEntity<ApiError> handleUpstreamUnavailable(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("CREDIT_LIMIT_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(ModelController.BaselineUnavailableException.class)
    public ResponseEntity<ApiError> handleBaselineUnavailable(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error("BASELINE_UNAVAILABLE", ex.getMessage()));
    }

    private ApiError error(String code, String message) {
        String traceId = MDC.get("traceId");
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        return new ApiError(code, message, traceId, Instant.now());
    }
}
