package com.bridgepay.applicant.dto;

import java.time.Instant;
import java.time.LocalDate;

/** Ops view of a shopper (case file, search), keyed by the Keycloak subject. No Paddle ids. */
public record OpsApplicantResponse(
        String subject,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dateOfBirth,
        Instant createdAt
) {
}
