package com.bridgepay.repayment.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Only the fields this service actually needs from a Paddle webhook's "data"
 * object - the real payload carries many more (see Paddle's
 * transaction.completed docs), all ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleWebhookData(String id, @JsonProperty("subscription_id") String subscriptionId, List<Item> items,
                                @JsonProperty("origin") String origin) {

    @JsonCreator
    public PaddleWebhookData {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public PaddleWebhookData(String id, String subscriptionId) {
        this(id, subscriptionId, List.of(), null);
    }

    public PaddleWebhookData(String id, String subscriptionId, List<Item> items) {
        this(id, subscriptionId, items, null);
    }

    /** A one-time charge we created (pay early), as opposed to a weekly renewal ("subscription_recurring"). */
    public boolean isSubscriptionCharge() {
        return "subscription_charge".equals(origin);
    }

    /**
     * Installments this transaction pays: an early-payment charge is one item whose quantity is the number of
     * installments (every installment of a plan has the same amount); a weekly renewal is quantity 1.
     */
    public int installmentsCovered() {
        return Math.max(1, items.stream().mapToInt(Item::quantity).sum());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(int quantity) {
    }
}
