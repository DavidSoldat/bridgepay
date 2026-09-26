package com.bridgepay.creditrisk.scoring;

/**
 * Thrown when scoring is attempted but no ONNX model has been loaded (missing
 * or unreadable model resources) or the inference call itself fails. Caught
 * by ScoringService and treated the same as any other fail-safe trigger.
 */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
