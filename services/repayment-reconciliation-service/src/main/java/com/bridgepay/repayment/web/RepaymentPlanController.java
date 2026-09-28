package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.service.RepaymentPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repayment-plans")
public class RepaymentPlanController {

    private final RepaymentPlanService repaymentPlanService;

    public RepaymentPlanController(RepaymentPlanService repaymentPlanService) {
        this.repaymentPlanService = repaymentPlanService;
    }

    @GetMapping("/{applicationId}")
    public ResponseEntity<RepaymentPlanResponse> get(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                     @PathVariable UUID applicationId) {
        boolean ops = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_OPS".equals(authority.getAuthority()));
        RepaymentPlanResponse response = ops
                ? repaymentPlanService.getForOps(applicationId)
                : repaymentPlanService.getForApplicant(applicationId, UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok(response);
    }
}
