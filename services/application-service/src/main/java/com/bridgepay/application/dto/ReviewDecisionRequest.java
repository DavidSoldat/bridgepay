package com.bridgepay.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ReviewDecisionRequest(
        @NotBlank(message = "decision is required")
        @Pattern(regexp = "APPROVE|DECLINE", message = "decision must be APPROVE or DECLINE")
        String decision,

        String reviewerNote
) {
}
