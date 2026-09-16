package com.bridgepay.creditrisk.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRepaymentHistoryClientTest {

    @Test
    void fetchHistory_throwsRepaymentHistoryUnavailable_whenRepaymentReconciliationIsUnreachable() {
        // Port 1 is a reserved/unused port - connection should fail fast.
        HttpRepaymentHistoryClient client = new HttpRepaymentHistoryClient(RestClient.builder(), "http://localhost:1");

        assertThatThrownBy(() -> client.fetchHistory(UUID.randomUUID()))
                .isInstanceOf(RepaymentHistoryUnavailableException.class);
    }
}
