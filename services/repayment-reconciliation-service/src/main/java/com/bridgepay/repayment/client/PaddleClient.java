package com.bridgepay.repayment.client;

import java.math.BigDecimal;
import java.util.Optional;

public interface PaddleClient {

    /** Looks up an existing Paddle customer by email, creating one if none exists. */
    String findOrCreateCustomer(String email, String name);

    /**
     * Creates a weekly-recurring, non-catalog-price transaction for the given
     * customer. Paddle only creates the actual subscription once this
     * transaction completes via checkout (see PaddleTransactionResult#checkoutUrl).
     */
    PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount);

    /** Cancels a subscription immediately - called once the final installment clears. */
    void cancelSubscription(String subscriptionId);

    /** Cancels a not-yet-paid transaction (Paddle allows this for draft/ready only) - used when installment 1 expires. */
    void cancelTransaction(String transactionId);

    /** The transaction's id and subscription id if Paddle reports it completed - what transaction.completed carries. */
    Optional<PaddleWebhookData> findCompletedTransaction(String transactionId);
}
