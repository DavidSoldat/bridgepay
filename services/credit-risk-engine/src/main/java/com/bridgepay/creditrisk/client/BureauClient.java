package com.bridgepay.creditrisk.client;

import java.util.UUID;

/**
 * Port to the Mock Credit Bureau Service, which doesn't exist as a running
 * service yet (see PROGRESS.md). HttpBureauClient is the real implementation;
 * until that service is deployed, every call fails over via its circuit
 * breaker and throws BureauUnavailableException, which ScoringService treats
 * the same as any other fail-safe trigger - routes to MANUAL_REVIEW.
 */
public interface BureauClient {
    BureauProfile fetchProfile(UUID applicantId);
}
