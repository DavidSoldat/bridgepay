package com.bridgepay.applicant.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ApplicantResponse(
        UUID id,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String email,
        String phone,
        Instant createdAt
) {
}
