package com.bridgepay.application;

import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.domain.MerchantPayout;
import com.bridgepay.application.domain.PayoutStatus;
import com.bridgepay.application.event.EventEnvelope;
import com.bridgepay.application.event.RepaymentEvents;
import com.bridgepay.application.repository.CreditApplicationRepository;
import com.bridgepay.application.repository.MerchantPayoutRepository;
import com.bridgepay.application.repository.MerchantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Postgres + Kafka (TestcontainersConfiguration); events are published to the live broker. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RepaymentEventConsumerIntegrationTest.TestOverrides.class})
class RepaymentEventConsumerIntegrationTest {

    @TestConfiguration
    static class TestOverrides {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }

        @Bean
        @Primary
        CreditRiskClient approveEverything() {
            return request -> new ScoreResult(0.1, ScoreDecision.APPROVE, List.of());
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    MerchantRepository merchantRepository;
    @Autowired
    MerchantPayoutRepository merchantPayoutRepository;
    @Autowired
    CreditApplicationRepository applicationRepository;

    private UUID approvedCheckout() throws Exception {
        Merchant merchant = merchantRepository.save(new Merchant("Merchant C", new BigDecimal("3.50")));
        String body = objectMapper.writeValueAsString(
                Map.of("merchantId", merchant.getId().toString(), "amount", "100.00"));
        String response = mockMvc.perform(post("/api/v1/applications")
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return UUID.fromString(json.get("applicationId").asText());
    }

    private void publish(String topic, Object payload) {
        UUID key = UUID.randomUUID();
        kafkaTemplate.send(topic, key.toString(), objectMapper.writeValueAsString(EventEnvelope.of(topic, key, payload)));
    }

    private MerchantPayout payout(UUID applicationId) {
        return merchantPayoutRepository.findByApplicationId(applicationId).orElseThrow();
    }

    private void installmentPaid(UUID applicationId) {
        publish("repayments.installment-paid", new RepaymentEvents.InstallmentPaid(
                UUID.randomUUID(), applicationId, UUID.randomUUID(), 1, new BigDecimal("25.00")));
    }

    @Test
    void approval_createsAPendingPayout_andInstallmentOneMarksItPaid() throws Exception {
        UUID applicationId = approvedCheckout();
        assertThat(payout(applicationId).getStatus()).isEqualTo(PayoutStatus.PENDING);
        assertThat(payout(applicationId).getPaidAt()).isNull();

        installmentPaid(applicationId);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(payout(applicationId).getStatus()).isEqualTo(PayoutStatus.PAID);
            assertThat(payout(applicationId).getPaidAt()).isNotNull();
        });
    }

    @Test
    void redeliveredInstallmentPaid_keepsTheFirstPaidAt() throws Exception {
        UUID applicationId = approvedCheckout();
        installmentPaid(applicationId);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> payout(applicationId).getStatus() == PayoutStatus.PAID);
        Instant firstPaidAt = payout(applicationId).getPaidAt();

        installmentPaid(applicationId);

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(payout(applicationId).getPaidAt()).isEqualTo(firstPaidAt));
    }

    @Test
    void planCancelled_cancelsTheApplicationAndItsPendingPayout() throws Exception {
        UUID applicationId = approvedCheckout();

        publish("repayments.plan-cancelled", new RepaymentEvents.PlanCancelled(UUID.randomUUID(), applicationId));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus())
                    .isEqualTo(ApplicationStatus.CANCELLED);
            assertThat(payout(applicationId).getStatus()).isEqualTo(PayoutStatus.CANCELLED);
        });
    }

    @Test
    void planCancelled_afterPayment_changesNothing() throws Exception {
        UUID applicationId = approvedCheckout();
        installmentPaid(applicationId);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> payout(applicationId).getStatus() == PayoutStatus.PAID);

        publish("repayments.plan-cancelled", new RepaymentEvents.PlanCancelled(UUID.randomUUID(), applicationId));

        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(applicationRepository.findById(applicationId).orElseThrow().getStatus())
                    .isEqualTo(ApplicationStatus.APPROVED);
            assertThat(payout(applicationId).getStatus()).isEqualTo(PayoutStatus.PAID);
        });
    }

    @Test
    void installmentPaid_withoutApplicationId_isSkipped_andLaterMessagesStillFlow() throws Exception {
        UUID applicationId = approvedCheckout();
        // the shape published before applicationId existed
        UUID key = UUID.randomUUID();
        kafkaTemplate.send("repayments.installment-paid", key.toString(), objectMapper.writeValueAsString(
                EventEnvelope.of("repayments.installment-paid", key,
                        Map.of("applicantId", UUID.randomUUID().toString(),
                                "installmentId", UUID.randomUUID().toString(),
                                "sequenceNumber", 1, "amount", 25.00))));

        installmentPaid(applicationId);

        await().atMost(Duration.ofSeconds(20))
                .until(() -> payout(applicationId).getStatus() == PayoutStatus.PAID);
    }
}
