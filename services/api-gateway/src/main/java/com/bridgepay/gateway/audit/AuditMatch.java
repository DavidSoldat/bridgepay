package com.bridgepay.gateway.audit;

/** Which audited action a request was, and what it touched. */
public record AuditMatch(AuditAction action, String targetType, String targetId, String detail) {
}
