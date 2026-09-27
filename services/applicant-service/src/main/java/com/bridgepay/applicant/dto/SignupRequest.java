package com.bridgepay.applicant.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

public record SignupRequest(

        @NotBlank(message = "firstName is required")
        String firstName,

        @NotBlank(message = "lastName is required")
        String lastName,

        @NotNull(message = "dateOfBirth is required")
        @Past(message = "dateOfBirth must be in the past")
        LocalDate dateOfBirth,

        @NotBlank(message = "email is required")
        // regexp: require a dot in the domain - Paddle rejects `name@example` when creating the customer
        @Email(regexp = ".+@.+\\..+", message = "email must be a valid email address")
        String email,

        @NotBlank(message = "phone is required")
        @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "phone must be a valid phone number")
        String phone
) {

    /** Credit needs legal capacity to contract; the storefront enforces the same rule. */
    public static final int MINIMUM_AGE = 18;

    @AssertTrue(message = "applicant must be at least 18 years old")
    public boolean isAdult() {
        return dateOfBirth == null || !dateOfBirth.isAfter(LocalDate.now().minusYears(MINIMUM_AGE));
    }
}
