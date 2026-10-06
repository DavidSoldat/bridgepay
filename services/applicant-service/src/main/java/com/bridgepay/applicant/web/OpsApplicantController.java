package com.bridgepay.applicant.web;

import com.bridgepay.applicant.dto.OpsApplicantResponse;
import com.bridgepay.applicant.service.ApplicantService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ops: who is paying us. Keyed by the Keycloak subject, the platform-wide applicantId. */
@RestController
@RequestMapping("/api/v1/ops/applicants")
public class OpsApplicantController {

    private final ApplicantService applicantService;

    public OpsApplicantController(ApplicantService applicantService) {
        this.applicantService = applicantService;
    }

    @GetMapping
    @PreAuthorize("hasRole('OPS')")
    public ResponseEntity<Page<OpsApplicantResponse>> search(@RequestParam String q,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(applicantService.search(q, page, size));
    }

    @GetMapping("/{subject}")
    @PreAuthorize("hasRole('OPS')")
    public ResponseEntity<OpsApplicantResponse> get(@PathVariable String subject) {
        return ResponseEntity.ok(applicantService.getForOps(subject));
    }
}
