package com.bridgepay.application.client;

/**
 * The Credit Risk Engine doesn't exist as a running service yet. This
 * interface is the contract Application Service codes against; tests use a
 * stub implementation, and HttpCreditRiskClient is the real one that will be
 * exercised once the Credit Risk Engine is built and deployed.
 */
public interface CreditRiskClient {
    ScoreResult score(ScoreRequest request);
}
