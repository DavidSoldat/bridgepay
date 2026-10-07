package com.bridgepay.gateway.audit;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validated /api/v1/audit filters, all optional and ANDed. Dates are inclusive calendar days in {@code tz}. */
public record AuditQuery(String actor, AuditAction action, String targetType, String targetId,
                         Instant fromInclusive, Instant toExclusive, String outcome) {

    private static final Set<String> TARGET_TYPES = Set.of("SHOPPER", "APPLICATION", "FAILED_EVENT", "MERCHANT");
    private static final Set<String> OUTCOMES = Set.of("ALLOWED", "DENIED", "FAILED");

    public static AuditQuery parse(String actor, String action, String targetType, String targetId,
                                   String from, String to, String tz, String outcome) {
        if ((targetType == null) != (targetId == null)) {
            throw new InvalidAuditQueryException("targetType and targetId go together");
        }
        if (targetType != null && !TARGET_TYPES.contains(targetType)) {
            throw new InvalidAuditQueryException("targetType must be one of SHOPPER, APPLICATION, FAILED_EVENT, MERCHANT");
        }
        if (outcome != null && !OUTCOMES.contains(outcome)) {
            throw new InvalidAuditQueryException("outcome must be ALLOWED, DENIED or FAILED");
        }
        AuditAction parsedAction;
        try {
            parsedAction = action == null ? null : AuditAction.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw new InvalidAuditQueryException("Unknown action " + action);
        }
        ZoneId zone = parseZone(tz);
        LocalDate fromDate = parseDate("from", from);
        LocalDate toDate = parseDate("to", to);
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidAuditQueryException("from must not be after to");
        }
        return new AuditQuery(blankToNull(actor), parsedAction, targetType, targetId,
                fromDate == null ? null : fromDate.atStartOfDay(zone).toInstant(),
                toDate == null ? null : toDate.plusDays(1).atStartOfDay(zone).toInstant(),
                outcome);
    }

    public Specification<AuditEntry> toSpecification() {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (actor != null) ps.add(cb.equal(root.get("actorUsername"), actor));
            if (action != null) ps.add(cb.equal(root.get("action"), action.name()));
            if (targetType != null) {
                ps.add(cb.equal(root.get("targetType"), targetType));
                ps.add(cb.equal(root.get("targetId"), targetId));
            }
            if (fromInclusive != null) ps.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), fromInclusive));
            if (toExclusive != null) ps.add(cb.lessThan(root.get("occurredAt"), toExclusive));
            if (outcome != null) {
                switch (outcome) {
                    case "ALLOWED" -> ps.add(cb.lessThan(root.get("status"), 400));
                    case "DENIED" -> ps.add(cb.equal(root.get("status"), 403));
                    default -> ps.add(cb.and(cb.greaterThanOrEqualTo(root.get("status"), 400),
                            cb.notEqual(root.get("status"), 403)));
                }
            }
            return cb.and(ps.toArray(Predicate[]::new));
        };
    }

    /** Only tz database names (same rule as application-service's dashboards); missing means UTC. */
    private static ZoneId parseZone(String tz) {
        if (tz == null) return ZoneId.of("UTC");
        if (!ZoneId.getAvailableZoneIds().contains(tz)) {
            throw new InvalidAuditQueryException("tz must be a time zone name like Europe/Belgrade");
        }
        return ZoneId.of(tz);
    }

    private static LocalDate parseDate(String name, String value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidAuditQueryException(name + " must be a date like 2026-10-07");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
