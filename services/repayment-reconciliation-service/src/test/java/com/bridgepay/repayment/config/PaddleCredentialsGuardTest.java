package com.bridgepay.repayment.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaddleCredentialsGuardTest {

    @Test
    void refusesPlaceholderWebhookSecretWhenRequired() {
        assertThatThrownBy(() -> new PaddleCredentialsGuard(true, "real-key", "changeme"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paddle.webhook-secret");
    }

    @Test
    void refusesPlaceholderOrBlankApiKeyWhenRequired() {
        assertThatThrownBy(() -> new PaddleCredentialsGuard(true, "changeme", "real-secret"))
                .hasMessageContaining("paddle.api-key");
        assertThatThrownBy(() -> new PaddleCredentialsGuard(true, " ", "real-secret"))
                .hasMessageContaining("paddle.api-key");
    }

    @Test
    void acceptsRealValuesWhenRequired() {
        assertThatCode(() -> new PaddleCredentialsGuard(true, "pdl_sdbx_apikey", "pdl_ntfset_secret"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsPlaceholdersWhenNotRequired() {
        assertThatCode(() -> new PaddleCredentialsGuard(false, "changeme", "changeme"))
                .doesNotThrowAnyException();
    }
}
