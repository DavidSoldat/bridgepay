package com.bridgepay.application.web;

import com.bridgepay.application.dto.MerchantPayoutResponse;
import com.bridgepay.application.dto.MerchantSaleResponse;
import com.bridgepay.application.dto.MerchantDashboardResponse;
import com.bridgepay.application.service.CreditApplicationService;
import com.bridgepay.application.service.MerchantDashboardService;
import org.springframework.data.domain.Page;
import com.bridgepay.application.service.SalesCsv;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final CreditApplicationService applicationService;
    private final MerchantDashboardService dashboardService;

    private final SalesCsv salesCsv;

    public MerchantController(CreditApplicationService applicationService, MerchantDashboardService dashboardService,
                              SalesCsv salesCsv) {
        this.applicationService = applicationService;
        this.dashboardService = dashboardService;
        this.salesCsv = salesCsv;
    }

    @GetMapping("/{id}/payouts")
    @PreAuthorize("hasRole('MERCHANT')")
    public Page<MerchantPayoutResponse> payouts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, Pageable pageable) {
        requireOwnMerchant(jwt, id);
        return applicationService.listPayoutsForMerchant(id, pageable);
    }

    @GetMapping("/{id}/sales")
    @PreAuthorize("hasRole('MERCHANT')")
    public Page<MerchantSaleResponse> sales(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "ALL") String status, Pageable pageable) {
        requireOwnMerchant(jwt, id);
        return applicationService.listSalesForMerchant(id, status, pageable);
    }

    @GetMapping("/{id}/sales/export")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<String> exportSales(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                              @RequestParam(defaultValue = "ALL") String status) {
        requireOwnMerchant(jwt, id);
        String body = salesCsv.export(id, status);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"bridgepay-sales-" + LocalDate.now(ZoneOffset.UTC) + ".csv\"")
                .body(body);
    }

    @PostMapping("/{id}/orders/{applicationId}/refund")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<MerchantSaleResponse> refund(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                       @PathVariable UUID applicationId) {
        requireOwnMerchant(jwt, id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(applicationService.requestRefund(id, applicationId));
    }

    @GetMapping("/{id}/dashboard")
    @PreAuthorize("hasRole('MERCHANT')")
    public MerchantDashboardResponse dashboard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                               @RequestParam int days, @RequestParam(required = false) String tz) {
        requireOwnMerchant(jwt, id);
        return dashboardService.dashboard(id, days, tz);
    }

    private void requireOwnMerchant(Jwt jwt, UUID id) {
        String jwtMerchantId = jwt.getClaimAsString("merchantId");
        if (jwtMerchantId == null || !jwtMerchantId.equals(id.toString())) {
            throw new AccessDeniedException("Not authorized for this merchant's data");
        }
    }
}
