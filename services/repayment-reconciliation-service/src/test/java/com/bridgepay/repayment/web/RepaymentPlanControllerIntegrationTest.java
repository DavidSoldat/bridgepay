package com.bridgepay.repayment.web;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import com.bridgepay.repayment.service.PaddleWebhookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.bridgepay.repayment.client.PaddlePaymentRefusedException;
import com.bridgepay.repayment.client.PaddleUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real Postgres + Kafka via Testcontainers, matching this service's other
 * @SpringBootTest classes (its Kafka consumer needs a broker to start).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RepaymentPlanControllerIntegrationTest.TestConfig.class)
class RepaymentPlanControllerIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay")
            .withUsername("test")
            .withPassword("test");

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:latest"));

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgresContainer() {
            return POSTGRES;
        }

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return KAFKA;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    // never the real sandbox from a test; unstubbed = "not completed yet"
    @MockitoBean
    private PaddleClient paddleClient;
    @Autowired
    private RepaymentPlanRepository repaymentPlanRepository;
    @Autowired
    private InstallmentRepository installmentRepository;

    @Test
    void get_returnsThePlanWithInstallments_whenItBelongsToTheAuthenticatedShopper() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        RepaymentPlan plan = repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.fromString(subject),
                "ctm_1", "txn_1", new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        installmentRepository.save(new Installment(plan, 1, LocalDate.of(2026, 1, 1), new BigDecimal("50.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()))
                .andExpect(jsonPath("$.installments.length()").value(1))
                .andExpect(jsonPath("$.installments[0].sequenceNumber").value(1));
    }

    @Test
    void get_returnsForbidden_whenThePlanBelongsToSomeoneElse() throws Exception {
        UUID applicationId = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.randomUUID(),
                "ctm_2", "txn_2", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Autowired
    private PaddleWebhookService paddleWebhookService;

    private RepaymentPlan planWithFourInstallments(UUID applicationId, String subject, String transactionId) {
        RepaymentPlan plan = repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.fromString(subject),
                "ctm_x", transactionId, new BigDecimal("200.00"), 4, new BigDecimal("50.00")));
        for (int sequence = 1; sequence <= 4; sequence++) {
            installmentRepository.save(new Installment(plan, sequence,
                    LocalDate.of(2026, 1, 1).plusWeeks(sequence - 1L), new BigDecimal("50.00")));
        }
        return plan;
    }

    @Test
    void checkoutTransactionId_isTheInitialTransaction_whileTheFirstInstallmentIsUnpaid() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_unpaid");

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutTransactionId").value("txn_unpaid"));
    }

    @Test
    void checkoutTransactionId_survivesADeclinedFirstAttempt() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_declined");

        paddleWebhookService.handle("transaction.payment_failed", new PaddleWebhookData("txn_declined", null));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("LATE"))
                .andExpect(jsonPath("$.checkoutTransactionId").value("txn_declined"));
    }

    @Test
    void firstPayment_adoptsTheSubscription_soALaterCancelDefaultsThePlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_first");

        paddleWebhookService.handle("transaction.completed", new PaddleWebhookData("txn_first", "sub_first"));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());

        paddleWebhookService.handle("subscription.canceled", new PaddleWebhookData("sub_first", null));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.status").value("DEFAULTED"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());
    }

    @Test
    void get_returnsNotFound_whenNoPlanExistsForThisApplication() throws Exception {
        mockMvc.perform(get("/api/v1/repayment-plans/" + UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
    }

    @Test
    void get_letsOpsReadAnyShoppersPlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        repaymentPlanRepository.save(new RepaymentPlan(applicationId, UUID.randomUUID(),
                "ctm_ops", "txn_ops", new BigDecimal("100.00"), 4, new BigDecimal("25.00")));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId)
                        .with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value(applicationId.toString()));
    }

    @Test
    void read_appliesAFirstPaymentPaddleCompleted_andALateWebhookThenChangesNothing() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_lost_webhook");
        PaddleWebhookData completed = new PaddleWebhookData("txn_lost_webhook", "sub_lost_webhook");
        when(paddleClient.findCompletedTransaction("txn_lost_webhook")).thenReturn(Optional.of(completed));

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.checkoutTransactionId").doesNotExist());

        paddleWebhookService.handle("transaction.completed", completed);

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[0].status").value("PAID"))
                .andExpect(jsonPath("$.installments[1].status").value("SCHEDULED"));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Four $50 installments, the first already paid through Paddle, subscription adopted. */
    private RepaymentPlan activePlanWithFirstPaid(UUID applicationId, String subject, String subscriptionId) {
        RepaymentPlan plan = planWithFourInstallments(applicationId, subject, "txn_first_" + subscriptionId);
        paddleWebhookService.handle("transaction.completed",
                new PaddleWebhookData("txn_first_" + subscriptionId, subscriptionId));
        return plan;
    }

    private org.springframework.test.web.servlet.ResultActions payEarly(UUID applicationId, String subject, String scope)
            throws Exception {
        return mockMvc.perform(post("/api/v1/repayment-plans/" + applicationId + "/early-payment")
                .with(jwt().jwt(j -> j.subject(subject)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"" + scope + "\"}"));
    }

    private static PaddleWebhookData charge(String txn, String sub, int quantity) {
        return new PaddleWebhookData(txn, sub, List.of(new PaddleWebhookData.Item(quantity)), "subscription_charge");
    }

    @Test
    void payEarly_next_chargesOneInstallment_andShowsItPaid() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_next");
        when(paddleClient.findLatestChargeTransaction("sub_next"))
                .thenReturn(Optional.empty(), Optional.of(charge("txn_next", "sub_next", 1)));

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.installments[1].status").value("PAID"))
                .andExpect(jsonPath("$.installments[2].status").value("SCHEDULED"));
        verify(paddleClient).chargeNow("sub_next", new BigDecimal("50.00"), 1);
    }

    @Test
    void payEarly_remaining_paysEverythingLeft_completesThePlan_andCancelsTheSubscription() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_rest");
        when(paddleClient.findLatestChargeTransaction("sub_rest"))
                .thenReturn(Optional.empty(), Optional.of(charge("txn_rest", "sub_rest", 3)));

        payEarly(applicationId, subject, "REMAINING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.installments[3].status").value("PAID"));
        verify(paddleClient).chargeNow("sub_rest", new BigDecimal("50.00"), 3);
        verify(paddleClient).cancelSubscription("sub_rest");
    }

    @Test
    void payEarly_thenPaddlesOwnWebhookForTheSameCharge_changesNothing() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_dup");
        PaddleWebhookData next = charge("txn_dup", "sub_dup", 1);
        when(paddleClient.findLatestChargeTransaction("sub_dup")).thenReturn(Optional.empty(), Optional.of(next));
        payEarly(applicationId, subject, "NEXT").andExpect(status().isOk());

        paddleWebhookService.handle("transaction.completed", next);

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[1].status").value("PAID"))
                .andExpect(jsonPath("$.installments[2].status").value("SCHEDULED"));
    }

    @Test
    void payEarly_keepsTheClaim_whenTheChargeIsNotListedYet() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_slow");
        when(paddleClient.findLatestChargeTransaction("sub_slow")).thenReturn(Optional.empty());

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.installments[1].status").value("SCHEDULED"));
        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A payment for this plan is already in progress."));
        verify(paddleClient, times(1)).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_keepsTheClaim_whenTheLookupFails() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_lookup_down");
        when(paddleClient.findLatestChargeTransaction("sub_lookup_down"))
                .thenReturn(Optional.empty()) // the pre-charge check finds nothing pending
                .thenThrow(new PaddleUnavailableException("down", null));

        payEarly(applicationId, subject, "NEXT").andExpect(status().isAccepted());
        payEarly(applicationId, subject, "NEXT").andExpect(status().isConflict());
        verify(paddleClient, times(1)).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_ignoresAnOlderAlreadyAppliedCharge() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_old");
        PaddleWebhookData older = charge("txn_older", "sub_old", 1);
        paddleWebhookService.handle("transaction.completed", older); // installment 2 already paid by an earlier charge
        when(paddleClient.findLatestChargeTransaction("sub_old")).thenReturn(Optional.of(older));

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.installments[2].status").value("SCHEDULED"));
    }

    @Test
    void payEarly_isRefusedWhileAClaimIsHeld_butNotOnceItHasExpired() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        RepaymentPlan plan = activePlanWithFirstPaid(applicationId, subject, "sub_claim");
        when(paddleClient.findLatestChargeTransaction("sub_claim"))
                .thenReturn(Optional.empty(), Optional.of(charge("txn_claim", "sub_claim", 1)));

        jdbcTemplate.update("update repayment.repayment_plans set early_payment_claimed_at = now() where id = ?", plan.getId());
        payEarly(applicationId, subject, "NEXT").andExpect(status().isConflict());
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());

        jdbcTemplate.update("update repayment.repayment_plans set early_payment_claimed_at = now() - interval '3 minutes' where id = ?",
                plan.getId());
        payEarly(applicationId, subject, "NEXT").andExpect(status().isOk());
    }

    @Test
    void payEarly_isRefusedBeforeTheFirstPayment() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        planWithFourInstallments(applicationId, subject, "txn_not_yet");

        payEarly(applicationId, subject, "NEXT").andExpect(status().isConflict());
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_isRefusedWithAMissedPayment_andReleasesTheClaim() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_late");
        paddleWebhookService.handle("transaction.payment_failed", new PaddleWebhookData("txn_renewal", "sub_late"));

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "You have a missed payment that is being retried. Pay-early is available once it clears."));
        payEarly(applicationId, subject, "NEXT")
                .andExpect(jsonPath("$.message").value(
                        "You have a missed payment that is being retried. Pay-early is available once it clears."));
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_reportsAPaddleRefusal_andTheShopperCanTryAgain() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_refused");
        doThrow(new PaddlePaymentRefusedException("declined", null))
                .when(paddleClient).chargeNow(eq("sub_refused"), any(), anyInt());

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAYMENT_REFUSED"))
                .andExpect(jsonPath("$.message").value(
                        "Your payment couldn't be taken right now. Nothing was charged. Please try again later."));
        payEarly(applicationId, subject, "NEXT").andExpect(status().isUnprocessableEntity()); // claim released
    }

    @Test
    void payEarly_reportsPaddleBeingDown() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_down");
        doThrow(new PaddleUnavailableException("down", null)).when(paddleClient).chargeNow(eq("sub_down"), any(), anyInt());

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PAYMENT_UNAVAILABLE"));
        // a 5xx/timeout does not prove nothing was charged, so the claim stays
        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A payment for this plan is already in progress."));
        verify(paddleClient, times(1)).chargeNow(eq("sub_down"), any(), anyInt());
    }

    @Test
    void payEarly_isForbiddenOnSomeoneElsesPlan() throws Exception {
        UUID applicationId = UUID.randomUUID();
        activePlanWithFirstPaid(applicationId, UUID.randomUUID().toString(), "sub_other");

        payEarly(applicationId, UUID.randomUUID().toString(), "NEXT").andExpect(status().isForbidden());
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_rejectsAnUnknownOrMissingScope() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_scope");

        payEarly(applicationId, subject, "ALL")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/repayment-plans/" + applicationId + "/early-payment")
                        .with(jwt().jwt(j -> j.subject(subject)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void payEarly_returns202AndKeepsTheClaim_whenApplyingTheChargeFailsAfterPaddleTookTheMoney() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_apply_fail");
        when(paddleClient.findLatestChargeTransaction("sub_apply_fail"))
                .thenReturn(Optional.empty(), Optional.of(charge("txn_apply_fail", "sub_apply_fail", 3)));
        doThrow(new PaddleUnavailableException("down", null)).when(paddleClient).cancelSubscription("sub_apply_fail");

        payEarly(applicationId, subject, "REMAINING").andExpect(status().isAccepted());
        payEarly(applicationId, subject, "REMAINING")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A payment for this plan is already in progress."));
        verify(paddleClient, times(1)).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void concurrentDeliveriesOfTheSameCharge_applyItExactlyOnce() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_race");
        PaddleWebhookData next = charge("txn_race", "sub_race", 1);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Future<?>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                start.await();
                paddleWebhookService.handle("transaction.completed", next);
                return null;
            }));
        }
        start.countDown();
        for (var run : runs) {
            run.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        Integer applied = jdbcTemplate.queryForObject(
                "select count(*) from repayment.installments where paddle_transaction_id = ?", Integer.class, "txn_race");
        org.assertj.core.api.Assertions.assertThat(applied).isEqualTo(1);
        Integer paid = jdbcTemplate.queryForObject(
                "select count(*) from repayment.installments i join repayment.repayment_plans p on p.id = i.repayment_plan_id "
                        + "where p.application_id = ? and i.status = 'PAID'", Integer.class, applicationId);
        org.assertj.core.api.Assertions.assertThat(paid).isEqualTo(2);
    }

    @Test
    void payEarly_next_movesTheRemainingDueDatesAWeekEarlier() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_shift");
        when(paddleClient.findLatestChargeTransaction("sub_shift"))
                .thenReturn(Optional.empty(), Optional.of(charge("txn_shift", "sub_shift", 1)));
        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[2].dueDate").value("2026-01-15"));

        payEarly(applicationId, subject, "NEXT").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/repayment-plans/" + applicationId).with(jwt().jwt(j -> j.subject(subject))))
                .andExpect(jsonPath("$.installments[1].status").value("PAID"))
                .andExpect(jsonPath("$.installments[2].dueDate").value("2026-01-08"))
                .andExpect(jsonPath("$.installments[3].dueDate").value("2026-01-15"));
    }

    @Test
    void payEarly_appliesAnEarlierChargeThatWasNeverApplied_insteadOfChargingAgain() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_pending");
        when(paddleClient.findLatestChargeTransaction("sub_pending"))
                .thenReturn(Optional.of(charge("txn_pending", "sub_pending", 1)));

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.installments[1].status").value("PAID"))
                .andExpect(jsonPath("$.installments[2].status").value("SCHEDULED"));
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());
    }

    @Test
    void payEarly_doesNotCharge_whenItCannotCheckForAnEarlierCharge_andReleasesTheClaim() throws Exception {
        UUID applicationId = UUID.randomUUID();
        String subject = UUID.randomUUID().toString();
        activePlanWithFirstPaid(applicationId, subject, "sub_precheck_down");
        when(paddleClient.findLatestChargeTransaction("sub_precheck_down"))
                .thenThrow(new PaddleUnavailableException("down", null));

        payEarly(applicationId, subject, "NEXT")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("PAYMENT_UNAVAILABLE"));
        payEarly(applicationId, subject, "NEXT").andExpect(status().isServiceUnavailable()); // not 409: claim released
        verify(paddleClient, never()).chargeNow(anyString(), any(), anyInt());
    }
}
