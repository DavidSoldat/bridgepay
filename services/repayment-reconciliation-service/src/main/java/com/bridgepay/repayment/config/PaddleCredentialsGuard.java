package com.bridgepay.repayment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production refuses to start on placeholder Paddle credentials. With webhook-secret left at
 * "changeme" anyone could sign a forged webhook and mark installments paid. envFrom silently
 * skips a key missing from the Secret, so a missing key would otherwise fall back to the default.
 */
@Component
public class PaddleCredentialsGuard {

    private static final String PLACEHOLDER = "changeme";

    public PaddleCredentialsGuard(@Value("${paddle.require-credentials}") boolean required,
                                  @Value("${paddle.api-key}") String apiKey,
                                  @Value("${paddle.webhook-secret}") String webhookSecret) {
        if (!required) {
            return;
        }
        check("paddle.api-key", apiKey);
        check("paddle.webhook-secret", webhookSecret);
    }

    private static void check(String name, String value) {
        if (value == null || value.isBlank() || value.equals(PLACEHOLDER)) {
            throw new IllegalStateException(name + " is not set (placeholder or blank) but paddle.require-credentials is true");
        }
    }
}
