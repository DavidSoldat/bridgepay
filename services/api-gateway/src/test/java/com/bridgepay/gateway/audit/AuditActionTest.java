package com.bridgepay.gateway.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionTest {

    static final String U = "0192a3b4-c5d6-7e8f-9a0b-1c2d3e4f5a6b";
    static final String M = "0192a3b4-0000-7000-8000-000000000001";

    private Optional<AuditMatch> match(String method, String path, String... params) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        StringBuilder qs = new StringBuilder();
        for (int i = 0; i < params.length; i += 2) {
            req.addParameter(params[i], params[i + 1]);
            qs.append(i == 0 ? "" : "&").append(params[i]).append('=').append(params[i + 1]);
        }
        if (params.length > 0) req.setQueryString(qs.toString());
        return AuditAction.match(req);
    }

    @Test
    void mapsEveryAuditedRoute() {
        assertThat(match("GET", "/api/v1/ops/applicants", "q", "ana")).contains(new AuditMatch(AuditAction.SEARCH_SHOPPERS, null, null, "ana"));
        assertThat(match("GET", "/api/v1/ops/applicants/" + U)).contains(new AuditMatch(AuditAction.VIEW_SHOPPER_PROFILE, "SHOPPER", U, null));
        assertThat(match("GET", "/api/v1/applications/applicants/" + U)).contains(new AuditMatch(AuditAction.VIEW_SHOPPER_APPLICATIONS, "SHOPPER", U, null));
        assertThat(match("GET", "/api/v1/applications/applicants/" + U + "/credit-standing")).contains(new AuditMatch(AuditAction.VIEW_CREDIT_STANDING, "SHOPPER", U, null));
        assertThat(match("GET", "/api/v1/repayment-plans/applicants/" + U)).contains(new AuditMatch(AuditAction.VIEW_REPAYMENTS, "SHOPPER", U, null));
        assertThat(match("GET", "/api/v1/notifications/applicants/" + U)).contains(new AuditMatch(AuditAction.VIEW_NOTIFICATIONS, "SHOPPER", U, null));
        assertThat(match("GET", "/api/v1/applications/" + U + "/case")).contains(new AuditMatch(AuditAction.VIEW_CASE_FILE, "APPLICATION", U, null));
        assertThat(match("GET", "/api/v1/applications/" + U)).contains(new AuditMatch(AuditAction.VIEW_APPLICATION, "APPLICATION", U, null));
        assertThat(match("GET", "/api/v1/repayment-plans/" + U)).contains(new AuditMatch(AuditAction.VIEW_REPAYMENT_PLAN, "APPLICATION", U, null));
        assertThat(match("POST", "/api/v1/applications/" + U + "/review-decision")).contains(new AuditMatch(AuditAction.DECIDE_APPLICATION, "APPLICATION", U, null));
        assertThat(match("POST", "/api/v1/ops/failed-events/" + U + "/retry")).contains(new AuditMatch(AuditAction.RETRY_FAILED_EVENT, "FAILED_EVENT", U, null));
        assertThat(match("POST", "/api/v1/merchants/" + M + "/orders/" + U + "/refund")).contains(new AuditMatch(AuditAction.REFUND_ORDER, "APPLICATION", U, "merchantId=" + M));
        assertThat(match("GET", "/api/v1/merchants/" + M + "/sales/export", "status", "APPROVED")).contains(new AuditMatch(AuditAction.EXPORT_SALES, "MERCHANT", M, "APPROVED"));
        assertThat(match("GET", "/api/v1/merchants/" + M + "/sales/export")).contains(new AuditMatch(AuditAction.EXPORT_SALES, "MERCHANT", M, null));
        assertThat(match("GET", "/api/v1/audit", "actor", "ops1", "page", "2")).contains(new AuditMatch(AuditAction.VIEW_AUDIT_LOG, null, null, "actor=ops1&page=2"));
    }

    @Test
    void literalSegmentsAndUnlistedPathsNeverMatch() {
        assertThat(match("GET", "/api/v1/applications/me")).isEmpty();
        assertThat(match("GET", "/api/v1/applications/dashboard")).isEmpty();
        assertThat(match("GET", "/api/v1/applications/me/credit-limit")).isEmpty();
        assertThat(match("GET", "/api/v1/applications")).isEmpty();
        assertThat(match("GET", "/api/v1/ops/failed-events")).isEmpty();
        assertThat(match("GET", "/api/v1/merchants/" + M + "/sales")).isEmpty();
        assertThat(match("GET", "/api/v1/applications/not-a-uuid")).isEmpty();
        assertThat(match("POST", "/api/v1/applications/" + U)).isEmpty(); // wrong method
        assertThat(match("POST", "/api/v1/repayment-plans/" + U + "/early-payment")).isEmpty();
    }

    @Test
    void percentEncodedIdsStillMatch() {
        // "%30" is "0": the proxy forwards the raw path, the downstream decodes it to a real UUID.
        String encoded = "%30" + U.substring(1);
        assertThat(match("GET", "/api/v1/ops/applicants/" + encoded))
                .contains(new AuditMatch(AuditAction.VIEW_SHOPPER_PROFILE, "SHOPPER", U, null));
        assertThat(match("POST", "/api/v1/merchants/" + M + "/orders/" + encoded + "/refund"))
                .contains(new AuditMatch(AuditAction.REFUND_ORDER, "APPLICATION", U, "merchantId=" + M));
        // Upper-case UUIDs reach the same row downstream; store one spelling so per-target views find it.
        assertThat(match("GET", "/api/v1/ops/applicants/" + U.toUpperCase()))
                .contains(new AuditMatch(AuditAction.VIEW_SHOPPER_PROFILE, "SHOPPER", U, null));
    }

    @Test
    void detailIsTruncatedTo500() {
        String q = "x".repeat(600);
        assertThat(match("GET", "/api/v1/ops/applicants", "q", q).orElseThrow().detail()).hasSize(500);
    }
}
