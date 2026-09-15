package com.bridgepay.applicant.web;

import java.time.Instant;

public record ApiError(String error, String message, String traceId, Instant timestamp) {
}
