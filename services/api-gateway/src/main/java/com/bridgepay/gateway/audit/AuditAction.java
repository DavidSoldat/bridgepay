package com.bridgepay.gateway.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every audited route (spec "What is recorded"). {id} is the target, {m} a merchant id; both match only a
 * UUID, so literal segments like /applications/me or /applications/dashboard never match a variable.
 */
public enum AuditAction {
    SEARCH_SHOPPERS("GET", "/api/v1/ops/applicants", null, r -> r.getParameter("q")),
    VIEW_SHOPPER_PROFILE("GET", "/api/v1/ops/applicants/{id}", "SHOPPER", r -> null),
    VIEW_SHOPPER_APPLICATIONS("GET", "/api/v1/applications/applicants/{id}", "SHOPPER", r -> null),
    VIEW_CREDIT_STANDING("GET", "/api/v1/applications/applicants/{id}/credit-standing", "SHOPPER", r -> null),
    VIEW_REPAYMENTS("GET", "/api/v1/repayment-plans/applicants/{id}", "SHOPPER", r -> null),
    VIEW_NOTIFICATIONS("GET", "/api/v1/notifications/applicants/{id}", "SHOPPER", r -> null),
    VIEW_CASE_FILE("GET", "/api/v1/applications/{id}/case", "APPLICATION", r -> null),
    VIEW_APPLICATION("GET", "/api/v1/applications/{id}", "APPLICATION", r -> null),
    VIEW_REPAYMENT_PLAN("GET", "/api/v1/repayment-plans/{id}", "APPLICATION", r -> null),
    DECIDE_APPLICATION("POST", "/api/v1/applications/{id}/review-decision", "APPLICATION", r -> null),
    RETRY_FAILED_EVENT("POST", "/api/v1/ops/failed-events/{id}/retry", "FAILED_EVENT", r -> null),
    REFUND_ORDER("POST", "/api/v1/merchants/{m}/orders/{id}/refund", "APPLICATION", r -> null),
    EXPORT_SALES("GET", "/api/v1/merchants/{id}/sales/export", "MERCHANT", r -> r.getParameter("status")),
    VIEW_AUDIT_LOG("GET", "/api/v1/audit", null, HttpServletRequest::getQueryString);

    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    static final int MAX_DETAIL = 500;

    private final String method;
    private final Pattern pattern;
    private final boolean hasMerchant;
    private final String targetType;
    private final Function<HttpServletRequest, String> detail;

    AuditAction(String method, String template, String targetType, Function<HttpServletRequest, String> detail) {
        this.method = method;
        this.pattern = Pattern.compile(template
                .replace("{id}", "(?<id>" + UUID + ")")
                .replace("{m}", "(?<m>" + UUID + ")"));
        this.hasMerchant = template.contains("{m}");
        this.targetType = targetType;
        this.detail = detail;
    }

    public static Optional<AuditMatch> match(HttpServletRequest request) {
        // Decoded: the proxy forwards the raw path and the downstream decodes it, so "%30…" is the same UUID.
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        for (AuditAction action : values()) {
            if (!action.method.equals(request.getMethod())) continue;
            Matcher m = action.pattern.matcher(path);
            if (!m.matches()) continue;
            String targetId = action.targetType == null ? null : m.group("id").toLowerCase(Locale.ROOT);
            String detail = action.hasMerchant
                    ? "merchantId=" + m.group("m").toLowerCase(Locale.ROOT)
                    : action.detail.apply(request);
            return Optional.of(new AuditMatch(action, action.targetType, targetId, truncate(detail)));
        }
        return Optional.empty();
    }

    static String truncate(String s) {
        return s == null || s.length() <= MAX_DETAIL ? s : s.substring(0, MAX_DETAIL);
    }
}
