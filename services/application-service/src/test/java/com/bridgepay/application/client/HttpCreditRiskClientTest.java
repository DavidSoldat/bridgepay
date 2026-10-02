package com.bridgepay.application.client;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpCreditRiskClientTest {

    @Test
    void score_fallsBackToManualReview_whenCreditRiskEngineIsUnreachable() {
        // Port 1 is a reserved/unused port - connection should fail fast.
        HttpCreditRiskClient client = new HttpCreditRiskClient(RestClient.builder(), "http://localhost:1");

        ScoreResult result = client.score(new ScoreRequest(
                UUID.randomUUID(), new BigDecimal("50.00"), "general", Instant.now()));

        assertThat(result.decision()).isEqualTo(ScoreDecision.MANUAL_REVIEW);
    }

    @Test
    void creditLimit_isEmpty_whenCreditRiskEngineIsUnreachable() {
        HttpCreditRiskClient client = new HttpCreditRiskClient(RestClient.builder(), "http://localhost:1");

        assertThat(client.creditLimit(UUID.randomUUID())).isEmpty();
    }

    /** A hung engine (accepts, never answers) must not hold the caller - every product page asks for a limit. */
    @Test
    void creditLimit_givesUpOnAHungEngine_insteadOfWaitingForever() throws Exception {
        try (ServerSocket hung = new ServerSocket(0)) {
            HttpCreditRiskClient client = new HttpCreditRiskClient(
                    RestClient.builder(), "http://localhost:" + hung.getLocalPort());

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    assertThat(client.creditLimit(UUID.randomUUID())).isEmpty());
        }
    }
}
