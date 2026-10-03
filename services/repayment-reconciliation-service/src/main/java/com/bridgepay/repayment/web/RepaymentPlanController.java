package com.bridgepay.repayment.web;

import com.bridgepay.repayment.dto.EarlyPaymentRequest;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.service.EarlyPaymentService;
import com.bridgepay.repayment.service.RepaymentPlanService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/repayment-plans")
public class RepaymentPlanController {

    private final RepaymentPlanService repaymentPlanService;

    private final EarlyPaymentService earlyPaymentService;

    public RepaymentPlanController(RepaymentPlanService repaymentPlanService, EarlyPaymentService earlyPaymentService) {
        this.repaymentPlanService = repaymentPlanService;
        this.earlyPaymentService = earlyPaymentService;
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

    /** 200 = applied; 202 = Paddle took the payment but it isn't applied yet (shows once Paddle's webhook arrives). */
    @PostMapping("/{applicationId}/early-payment")
    public ResponseEntity<RepaymentPlanResponse> payEarly(@AuthenticationPrincipal Jwt jwt,
                                                          @PathVariable UUID applicationId,
                                                          @Valid @RequestBody EarlyPaymentRequest request) {
        EarlyPaymentService.Result result =
                earlyPaymentService.pay(applicationId, UUID.fromString(jwt.getSubject()), request.scope());
        return ResponseEntity.status(result.applied() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(result.plan());
    }
}
