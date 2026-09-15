package com.bridgepay.application.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HttpCreditRiskClientTest {

    @Test
    void score_fallsBackToManualReview_whenCreditRiskEngineIsUnreachable() {
        // Port 1 is a reserved/unused port - connection should fail fast.
        HttpCreditRiskClient client = new HttpCreditRiskClient(RestClient.builder(), "http://localhost:1");

        ScoreResult result = client.score(new ScoreRequest(
                UUID.randomUUID(), new BigDecimal("50.00"), "general", Instant.now()));

        assertThat(result.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
    }
}
