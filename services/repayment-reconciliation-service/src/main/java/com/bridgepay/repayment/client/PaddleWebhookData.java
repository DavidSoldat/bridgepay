package com.bridgepay.repayment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Only the fields this service actually needs from a Paddle webhook's "data"
 * object - the real payload carries many more (see Paddle's
 * transaction.completed docs), all ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleWebhookData(String id, @JsonProperty("subscription_id") String subscriptionId) {
}
