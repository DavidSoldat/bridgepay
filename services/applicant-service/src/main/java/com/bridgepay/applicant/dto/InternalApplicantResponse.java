package com.bridgepay.applicant.dto;

/**
 * {@code id} is the Keycloak subject - the platform-wide applicantId - not
 * this service's own primary key, so callers can pass it straight back to
 * the other internal endpoints.
 */
public record InternalApplicantResponse(
        String id,
        String firstName,
        String lastName,
        String email,
        String paddleCustomerId
) {
}
