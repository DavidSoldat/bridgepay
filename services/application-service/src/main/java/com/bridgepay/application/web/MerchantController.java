package com.bridgepay.application.web;

import com.bridgepay.application.dto.MerchantPayoutResponse;
import com.bridgepay.application.dto.MerchantSaleResponse;
import com.bridgepay.application.dto.MerchantDashboardResponse;
import com.bridgepay.application.service.CreditApplicationService;
import com.bridgepay.application.service.MerchantDashboardService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final CreditApplicationService applicationService;
    private final MerchantDashboardService dashboardService;

    public MerchantController(CreditApplicationService applicationService, MerchantDashboardService dashboardService) {
        this.applicationService = applicationService;
        this.dashboardService = dashboardService;
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
