package com.bridgepay.creditrisk.client;

import java.util.UUID;

/**
 * Port to the Mock Credit Bureau Service. HttpBureauClient is the real
 * implementation; when the bureau is unreachable, calls fail over via its
 * circuit breaker and throw BureauUnavailableException, which ScoringService
 * treats the same as any other fail-safe trigger - routes to MANUAL_REVIEW.
 */
public interface BureauClient {
    BureauProfile fetchProfile(UUID applicantId);
}
