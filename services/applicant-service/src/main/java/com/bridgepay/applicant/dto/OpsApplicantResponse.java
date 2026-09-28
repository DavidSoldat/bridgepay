package com.bridgepay.applicant.dto;

import java.time.LocalDate;

/** Ops case-file view of a shopper, keyed by the Keycloak subject. No Paddle ids. */
public record OpsApplicantResponse(
        String subject,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate dateOfBirth
) {
}
