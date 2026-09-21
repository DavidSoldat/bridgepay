package com.bridgepay.application.web;

import com.bridgepay.application.dto.ApplicationResponse;
import com.bridgepay.application.dto.CheckoutRequest;
import com.bridgepay.application.dto.ReviewDecisionRequest;
import com.bridgepay.application.service.CreditApplicationService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The applicantId used throughout this service is the Keycloak subject
 * (jwt.getSubject()), parsed as a UUID - Keycloak subject IDs are UUIDs by
 * default. This is a deliberate simplification: resolving to the Applicant
 * Service's own internal id would require a live cross-service call on the
 * critical checkout path, which is exactly the kind of extra failure point
 * the design otherwise avoids. Worth reconsidering only if the two ever need
 * to diverge.
 */
@RestController
@RequestMapping("/api/v1/applications")
public class CreditApplicationController {

    private final CreditApplicationService applicationService;

    public CreditApplicationController(CreditApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    public ResponseEntity<ApplicationResponse> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {
        UUID applicantId = UUID.fromString(jwt.getSubject());
        ApplicationResponse response = applicationService.checkout(applicantId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApplicationResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        boolean isOps = jwt.getClaimAsMap("realm_access") != null
                && ((java.util.List<?>) jwt.getClaimAsMap("realm_access").getOrDefault("roles", java.util.List.of()))
                        .contains("ops");
        ApplicationResponse response = isOps
                ? applicationService.getForOps(id)
                : applicationService.getForApplicant(UUID.fromString(jwt.getSubject()), id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('OPS')")
    public ResponseEntity<Page<ApplicationResponse>> listManualReview(
            @RequestParam(defaultValue = "MANUAL_REVIEW") String status,
            Pageable pageable) {
        return ResponseEntity.ok(applicationService.listApplications(status, pageable));
    }

    @PostMapping("/{id}/review-decision")
    @PreAuthorize("hasRole('OPS')")
    public ResponseEntity<ApplicationResponse> reviewDecision(
            @PathVariable UUID id,
            @Valid @RequestBody ReviewDecisionRequest request) {
        return ResponseEntity.ok(applicationService.reviewDecision(id, request));
    }
}
