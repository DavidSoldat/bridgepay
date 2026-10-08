package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.OutboxEventRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(RefundServiceIntegrationTest.TestConfig.class)
class RefundServiceIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bridgepay").withUsername("test").withPassword("test");
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
    static final RecordingPaddleClient PADDLE = new RecordingPaddleClient();

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
            return token -> Jwt.withTokenValue(token).header("alg", "none").claim("sub", "unused")
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }

        @Bean
        @Primary
        PaddleClient paddleClient() {
            return PADDLE;
        }
    }

    /** Records every call; refunds are idempotent per transaction like the real client. */
    static class RecordingPaddleClient implements PaddleClient {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final Map<String, String> refunds = new ConcurrentHashMap<>();
        final Map<String, PaddleWebhookData> completed = new ConcurrentHashMap<>();
        volatile String failRefundOf;
        volatile Runnable onCancelSubscription = () -> { };

        void reset() {
            calls.clear();
            refunds.clear();
            completed.clear();
            failRefundOf = null;
            onCancelSubscription = () -> { };
        }

        @Override
        public String findOrCreateCustomer(String email, String name) {
            return "ctm_unused";
        }

        @Override
        public PaddleTransactionResult createInstallmentTransaction(String customerId, BigDecimal installmentAmount) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public void cancelSubscription(String subscriptionId) {
            calls.add("cancelSubscription:" + subscriptionId);
            onCancelSubscription.run();
        }

        @Override
        public void cancelTransaction(String transactionId) {
            calls.add("cancelTransaction:" + transactionId);
        }

        @Override
        public Optional<PaddleWebhookData> findCompletedTransaction(String transactionId) {
            return Optional.ofNullable(completed.get(transactionId));
        }

        @Override
        public void chargeNow(String subscriptionId, BigDecimal installmentAmount, int quantity) {
            throw new UnsupportedOperationException("not used by these tests");
        }

        @Override
        public Optional<PaddleWebhookData> findLatestChargeTransaction(String subscriptionId) {
            return Optional.empty();
        }

        @Override
        public String refundTransaction(String transactionId) {
            if (transactionId.equals(failRefundOf)) {
                throw new com.bridgepay.repayment.client.PaddleUnavailableException("boom", null);
            }
            calls.add("refund:" + transactionId);
            return refunds.computeIfAbsent(transactionId, id -> "adj_" + id);
        }
    }

    @Autowired
    RefundService refundService;
    @Autowired
    PaddleWebhookService webhookService;
    @Autowired
    RepaymentPlanRepository planRepository;
    @Autowired
    InstallmentRepository installmentRepository;
    @Autowired
    OutboxEventRepository outboxEventRepository;
    @Autowired
    RepaymentHistoryService repaymentHistoryService;

    @BeforeEach
    void resetPaddle() {
        PADDLE.reset();
    }

    /** A 4 x 25.00 plan whose installment 1 is paid through txn_initial_<suffix>, subscription sub_<suffix>. */
    private RepaymentPlan plan(String suffix, int paidCount) {
        RepaymentPlan plan = new RepaymentPlan(UUID.randomUUID(), UUID.randomUUID(), "ctm_x",
                "txn_initial_" + suffix, new BigDecimal("100.00"), 4, new BigDecimal("25.00"));
        if (paidCount > 0) {
            plan.adoptSubscriptionId("sub_" + suffix);
        }
        planRepository.save(plan);
        for (int sequence = 1; sequence <= 4; sequence++) {
            Installment installment = new Installment(plan, sequence, LocalDate.now().plusWeeks(sequence - 1L),
                    new BigDecimal("25.00"));
            if (sequence <= paidCount) {
                installment.markPaid(sequence == 1 ? "txn_initial_" + suffix : "txn_" + suffix + "_" + sequence);
            }
            installmentRepository.save(installment);
        }
        return plan;
    }

    private RepaymentPlan reload(RepaymentPlan plan) {
        return planRepository.findById(plan.getId()).orElseThrow();
    }

    private List<InstallmentStatus> statuses(RepaymentPlan plan) {
        return installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan).stream()
                .map(Installment::getStatus).toList();
    }

    private long refundedEvents(RepaymentPlan plan) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getTopic().equals("repayments.plan-refunded"))
                .filter(e -> e.getPayload().contains(plan.getApplicationId().toString()))
                .count();
    }

    @Test
    void paddlesOwnCancelWebhook_arrivingMidRefund_leavesThePlanRefunded() throws Exception {
        RepaymentPlan plan = plan("race", 1);
        CountDownLatch insideCancel = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        PADDLE.onCancelSubscription = () -> {
            insideCancel.countDown();
            try {
                releaseCancel.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> refund = executor.submit(() -> refundService.refund(plan.getApplicationId()));
            assertThat(insideCancel.await(10, TimeUnit.SECONDS)).isTrue();

            // Paddle confirms our cancel before the refund transaction has committed.
            Future<?> webhook = executor.submit(() -> webhookService.handle("subscription.canceled",
                    new PaddleWebhookData("sub_race", null, null, null)));
            Thread.sleep(500);   // give an unlocked handler time to read ACTIVE and commit DEFAULTED
            releaseCancel.countDown();

            refund.get(20, TimeUnit.SECONDS);
            webhook.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
    }

    @Test
    void aRenewalCompletingAfterTheRefund_isIgnored() {
        RepaymentPlan plan = plan("renewal", 1);
        refundService.refund(plan.getApplicationId());

        webhookService.handle("transaction.completed", new PaddleWebhookData("txn_renewal_late", "sub_renewal", null, null));

        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
        assertThat(statuses(plan)).doesNotContain(InstallmentStatus.PAID);
        assertThat(installmentRepository.existsByPaddleTransactionId("txn_renewal_late")).isFalse();
    }

    @Test
    void unpaidPlan_cancelsTheCheckoutTransaction_refundsNothing() {
        RepaymentPlan plan = plan("unpaid", 0);

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).containsExactly("cancelTransaction:txn_initial_unpaid");
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
        assertThat(statuses(plan)).containsOnly(InstallmentStatus.CANCELLED);
        assertThat(outboxEventRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo("repayments.plan-refunded");
            assertThat(e.getPayload()).contains(plan.getApplicationId().toString()).contains("\"refundedAmount\":0");
        });
    }

    @Test
    void unpaidPlanThatPaddleReportsCompleted_isReconciledThenRefunded() {
        RepaymentPlan plan = plan("late_paid", 0);
        PADDLE.completed.put("txn_initial_late_paid",
                new PaddleWebhookData("txn_initial_late_paid", "sub_late_paid", null, null));

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).containsExactly("refund:txn_initial_late_paid", "cancelSubscription:sub_late_paid");
        assertThat(statuses(plan)).containsExactly(InstallmentStatus.REFUNDED, InstallmentStatus.CANCELLED,
                InstallmentStatus.CANCELLED, InstallmentStatus.CANCELLED);
    }

    @Test
    void payoffChargeCoveringTwoInstallments_isRefundedOnce_andTheSubscriptionCancelledLast() {
        RepaymentPlan plan = plan("payoff", 1);
        List<Installment> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
        installments.get(1).markPaid("txn_payoff_charge");
        installments.get(2).markPaid("txn_payoff_charge");
        installmentRepository.saveAll(installments);

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).containsExactly(
                "refund:txn_initial_payoff", "refund:txn_payoff_charge", "cancelSubscription:sub_payoff");
        assertThat(statuses(plan)).containsExactly(InstallmentStatus.REFUNDED, InstallmentStatus.REFUNDED,
                InstallmentStatus.REFUNDED, InstallmentStatus.CANCELLED);
        assertThat(installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan).get(2).getRefundAdjustmentId())
                .isEqualTo("adj_txn_payoff_charge");
        assertThat(outboxEventRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo("repayments.plan-refunded");
            assertThat(e.getPayload()).contains(plan.getApplicationId().toString()).contains("\"refundedAmount\":75.00");
        });
    }

    @Test
    void completedPlan_isRefunded_withoutCancellingTheAlreadyCancelledSubscription() {
        RepaymentPlan plan = plan("done", 4);
        plan.markCompleted();
        planRepository.save(plan);

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).hasSize(4).allMatch(call -> call.startsWith("refund:"));
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
    }

    @Test
    void alreadyRefundedPlan_isANoOp() {
        RepaymentPlan plan = plan("twice", 1);
        refundService.refund(plan.getApplicationId());
        PADDLE.calls.clear();

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).isEmpty();
        assertThat(refundedEvents(plan)).isEqualTo(1);
    }

    @Test
    void aPaymentCompletingOnARefundedPlan_isRefundedAutomatically_andPlanStaysRefunded() {
        RepaymentPlan plan = plan("late_pay", 1);
        refundService.refund(plan.getApplicationId());
        PADDLE.calls.clear();

        webhookService.handle("transaction.completed", new PaddleWebhookData("txn_new_late", "sub_late_pay", null, null));

        assertThat(PADDLE.calls).containsExactly("refund:txn_new_late");
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
    }

    @Test
    void theWebhookForATransactionTheRefundAlreadyHandled_isADuplicate_notRefundedAgain() {
        RepaymentPlan plan = plan("dup", 1);
        refundService.refund(plan.getApplicationId());
        PADDLE.calls.clear();

        webhookService.handle("transaction.completed", new PaddleWebhookData("txn_initial_dup", "sub_dup", null, null));

        assertThat(PADDLE.calls).isEmpty();
    }

    @Test
    void cancelledPlan_isAcknowledgedWithZero_withoutCallingPaddle() {
        RepaymentPlan plan = plan("cancelled", 0);
        plan.markCancelled();
        planRepository.save(plan);

        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.calls).isEmpty();
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.CANCELLED);
        assertThat(outboxEventRepository.findAll()).anySatisfy(e -> {
            assertThat(e.getTopic()).isEqualTo("repayments.plan-refunded");
            assertThat(e.getPayload()).contains(plan.getApplicationId().toString()).contains("\"refundedAmount\":0");
        });
    }

    @Test
    void refundRetriedAfterAPaddleFailure_doesNotRefundTheFirstTransactionTwice() {
        RepaymentPlan plan = plan("partial", 2);
        PADDLE.failRefundOf = "txn_partial_2";

        assertThatThrownBy(() -> refundService.refund(plan.getApplicationId()));
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.ACTIVE);

        PADDLE.failRefundOf = null;
        refundService.refund(plan.getApplicationId());

        assertThat(PADDLE.refunds).containsOnlyKeys("txn_initial_partial", "txn_partial_2");
        assertThat(reload(plan).getStatus()).isEqualTo(PlanStatus.REFUNDED);
    }

    @Test
    void defaultedPlan_isRejected() {
        RepaymentPlan plan = plan("defaulted", 1);
        plan.markDefaulted();
        planRepository.save(plan);

        assertThatThrownBy(() -> refundService.refund(plan.getApplicationId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(PADDLE.calls).isEmpty();
    }

    @Test
    void noPlanYet_isRejected() {
        assertThatThrownBy(() -> refundService.refund(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRefundedPlan_doesNotCountInTheShoppersRepaymentHistory() {
        RepaymentPlan plan = plan("history", 2);
        List<Installment> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
        installments.get(2).markLate();
        installmentRepository.save(installments.get(2));

        refundService.refund(plan.getApplicationId());

        var history = repaymentHistoryService.getHistory(plan.getApplicantId());
        assertThat(history.completedPlans()).isZero();
        assertThat(history.latePaymentCount()).isZero();
        assertThat(history.onTimeRate()).isEqualTo(1.0);
    }
}
