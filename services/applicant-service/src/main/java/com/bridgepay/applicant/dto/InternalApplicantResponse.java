package com.bridgepay.applicant.dto;

import java.util.UUID;

public record InternalApplicantResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String paddleCustomerId
) {
}
