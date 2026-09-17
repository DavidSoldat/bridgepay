package com.bridgepay.repayment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PaddleWebhookEnvelope(@JsonProperty("event_id") String eventId,
                                     @JsonProperty("event_type") String eventType,
                                     PaddleWebhookData data) {
}
