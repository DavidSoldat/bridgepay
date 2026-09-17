package com.bridgepay.applicant.dto;

import jakarta.validation.constraints.NotBlank;

public record SetPaddleCustomerRequest(
        @NotBlank String paddleCustomerId
) {
}
