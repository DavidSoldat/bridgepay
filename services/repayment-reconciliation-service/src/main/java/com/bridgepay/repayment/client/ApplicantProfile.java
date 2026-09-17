package com.bridgepay.repayment.client;

import java.util.UUID;

/** Mirrors Applicant Service's {@code GET /internal/applicants/{id}} response shape. */
public record ApplicantProfile(UUID id, String firstName, String lastName, String email, String paddleCustomerId) {

    public String fullName() {
        return firstName + " " + lastName;
    }
}
