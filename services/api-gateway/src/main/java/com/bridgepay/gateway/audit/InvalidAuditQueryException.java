package com.bridgepay.gateway.audit;

public class InvalidAuditQueryException extends RuntimeException {
    public InvalidAuditQueryException(String message) {
        super(message);
    }
}
