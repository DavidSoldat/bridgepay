package com.bridgepay.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @NotBlank(message = "decision is required")
        @Pattern(regexp = "APPROVE|DECLINE", message = "decision must be APPROVE or DECLINE")
        String decision,

        @Size(max = 1000, message = "reviewerNote must be at most 1000 characters")
        String reviewerNote
) {
}
