package com.bridgepay.creditrisk.client;

import java.util.UUID;

/**
 * Port to Repayment Reconciliation Service, not built yet (see PROGRESS.md).
 * Until it's deployed, every call fails over via its circuit breaker and
 * throws RepaymentHistoryUnavailableException, which ScoringService treats
 * the same as any other fail-safe trigger - routes to MANUAL_REVIEW.
 */
public interface RepaymentHistoryClient {
    RepaymentHistory fetchHistory(UUID applicantId);
}
