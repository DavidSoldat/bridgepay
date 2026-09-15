package com.bridgepay.applicant.web;

import com.bridgepay.applicant.dto.ApplicantResponse;
import com.bridgepay.applicant.dto.SignupRequest;
import com.bridgepay.applicant.service.ApplicantService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/applicants")
public class ApplicantController {

    private final ApplicantService applicantService;

    public ApplicantController(ApplicantService applicantService) {
        this.applicantService = applicantService;
    }

    @PostMapping
    public ResponseEntity<ApplicantResponse> signUp(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SignupRequest request) {
        ApplicantResponse response = applicantService.signUp(jwt.getSubject(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<ApplicantResponse> getMyProfile(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(applicantService.getBySubject(jwt.getSubject()));
    }
}
