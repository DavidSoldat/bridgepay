package com.bridgepay.applicant.web;

import com.bridgepay.applicant.dto.InternalApplicantResponse;
import com.bridgepay.applicant.dto.SetPaddleCustomerRequest;
import com.bridgepay.applicant.service.ApplicantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Internal-only: network isolation (plain ClusterIP, no Ingress) is the
 * access control here, not JWT - same trust model as Credit Risk Engine's
 * /internal/score. Used by Repayment Reconciliation Service to look up an
 * applicant's email/name for Paddle customer creation, and to persist the
 * resulting paddle_customer_id back once created.
 */
@RestController
@RequestMapping("/internal/applicants")
public class InternalApplicantController {

    private final ApplicantService applicantService;

    public InternalApplicantController(ApplicantService applicantService) {
        this.applicantService = applicantService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<InternalApplicantResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(applicantService.getById(id));
    }

    @PatchMapping("/{id}/paddle-customer")
    public ResponseEntity<Void> setPaddleCustomer(@PathVariable UUID id,
                                                    @Valid @RequestBody SetPaddleCustomerRequest request) {
        applicantService.setPaddleCustomerId(id, request.paddleCustomerId());
        return ResponseEntity.noContent().build();
    }
}
