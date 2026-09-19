package com.bridgepay.application.web;

import com.bridgepay.application.dto.MerchantPayoutResponse;
import com.bridgepay.application.service.CreditApplicationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
public class MerchantController {

    private final CreditApplicationService applicationService;

    public MerchantController(CreditApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping("/{id}/payouts")
    @PreAuthorize("hasRole('MERCHANT')")
    public Page<MerchantPayoutResponse> payouts(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, Pageable pageable) {
        String jwtMerchantId = jwt.getClaimAsString("merchantId");
        if (jwtMerchantId == null || !jwtMerchantId.equals(id.toString())) {
            throw new AccessDeniedException("Not authorized for this merchant's payouts");
        }
        return applicationService.listPayoutsForMerchant(id, pageable);
    }
}
