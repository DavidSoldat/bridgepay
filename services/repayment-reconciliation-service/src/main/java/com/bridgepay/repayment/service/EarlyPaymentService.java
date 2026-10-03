package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleUnavailableException;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pays installments ahead of schedule with a Paddle one-time charge on the plan's subscription. Not
 * @Transactional: no DB transaction is held across Paddle calls; the claim is what keeps two requests apart.
 */
@Service
public class EarlyPaymentService {

    private static final Logger log = LoggerFactory.getLogger(EarlyPaymentService.class);
    private static final int CHARGE_LOOKUP_ATTEMPTS = 3;
    static final String MISSED_PAYMENT =
            "You have a missed payment that is being retried. Pay-early is available once it clears.";

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final InstallmentRepository installmentRepository;
    private final PaddleClient paddleClient;
    private final PaddleWebhookService paddleWebhookService;
    private final RepaymentPlanService repaymentPlanService;
    private final Duration lookupDelay;

    public EarlyPaymentService(RepaymentPlanRepository repaymentPlanRepository,
                               InstallmentRepository installmentRepository,
                               PaddleClient paddleClient,
                               PaddleWebhookService paddleWebhookService,
                               RepaymentPlanService repaymentPlanService,
                               @Value("${paddle.charge-lookup-delay:PT1S}") Duration lookupDelay) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.installmentRepository = installmentRepository;
        this.paddleClient = paddleClient;
        this.paddleWebhookService = paddleWebhookService;
        this.repaymentPlanService = repaymentPlanService;
        this.lookupDelay = lookupDelay;
    }

    /** applied = false: Paddle took the payment but hasn't listed it yet; it shows once the webhook arrives. */
    public record Result(boolean applied, RepaymentPlanResponse plan) {
    }

    public Result pay(UUID applicationId, UUID applicantId, EarlyPaymentScope scope) {
        UUID planId = repaymentPlanService.getForApplicant(applicationId, applicantId).planId(); // 404 / 403
        if (repaymentPlanRepository.claimEarlyPayment(planId) == 0) {
            throw new IllegalStateException("A payment for this plan is already in progress.");
        }

        boolean charged = false;
        boolean applied = false;
        try {
            // Validated under the claim, so a request queued behind a payoff sees it paid and never charges again.
            RepaymentPlan plan = repaymentPlanRepository.findById(planId).orElseThrow();
            List<Installment> installments = requireActiveWithFirstPaid(plan);

            // A charge Paddle took but we never applied (its claim expired) still shows its installments SCHEDULED:
            // apply it instead of charging again. A failed lookup throws (503) before any charge - we can't tell.
            Optional<PaddleWebhookData> pending = unapplied(paddleClient.findLatestChargeTransaction(
                    plan.getPaddleSubscriptionId()));
            if (pending.isPresent()) {
                paddleWebhookService.handle("transaction.completed", pending.get());
                applied = true;
                return new Result(true, repaymentPlanService.getForApplicant(applicationId, applicantId));
            }

            int quantity = quantityFor(installments, scope);
            try {
                paddleClient.chargeNow(plan.getPaddleSubscriptionId(), plan.getInstallmentAmount(), quantity);
            } catch (PaddleUnavailableException ex) {
                // A 5xx or a timeout after the request was sent doesn't prove nothing was charged: keep the claim
                // (it expires on its own) so a retry can't double-charge. Only a 4xx refusal proves no charge.
                charged = true;
                throw ex;
            }
            charged = true;

            Optional<PaddleWebhookData> charge = findNewCharge(plan.getPaddleSubscriptionId());
            if (charge.isPresent()) {
                try {
                    paddleWebhookService.handle("transaction.completed", charge.get());
                    applied = true;
                } catch (RuntimeException ex) {
                    // Money is taken; the webhook (or reconcile-on-read) will apply it. Keep the claim, answer 202.
                    log.warn("Charged plan {} but applying the charge failed; keeping the claim until the webhook",
                            planId, ex);
                }
            } else {
                // ponytail: without a webhook (local, no tunnel) only the shopper's next pay-early attempt applies
                // this payment (its pre-charge check); upgrade path is reconcile-on-read checking it too.
                log.warn("Charged plan {} at Paddle but the charge isn't listed yet; keeping the claim until the webhook",
                        planId);
            }
        } finally {
            // Once Paddle has taken money, only a successful apply may release the claim (it expires on its own).
            if (!charged || applied) {
                repaymentPlanRepository.releaseEarlyPayment(planId);
            }
        }
        return new Result(applied, repaymentPlanService.getForApplicant(applicationId, applicantId));
    }

    private List<Installment> requireActiveWithFirstPaid(RepaymentPlan plan) {
        if (plan.getStatus() != PlanStatus.ACTIVE) {
            throw new IllegalStateException("This plan is no longer active.");
        }
        List<Installment> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
        if (installments.isEmpty() || installments.get(0).getStatus() != InstallmentStatus.PAID) {
            throw new IllegalStateException("Make your first payment before paying early.");
        }
        return installments;
    }

    private int quantityFor(List<Installment> installments, EarlyPaymentScope scope) {
        if (installments.stream().anyMatch(i -> i.getStatus() == InstallmentStatus.LATE)) {
            throw new IllegalStateException(MISSED_PAYMENT);
        }
        int scheduled = (int) installments.stream().filter(i -> i.getStatus() == InstallmentStatus.SCHEDULED).count();
        if (scheduled == 0) {
            throw new IllegalStateException("Nothing is left to pay on this plan.");
        }
        return scope == EarlyPaymentScope.NEXT ? 1 : scheduled;
    }

    /** The newest charge transaction that isn't already applied to an installment (an older charge isn't this one). */
    private Optional<PaddleWebhookData> findNewCharge(String subscriptionId) {
        for (int attempt = 1; attempt <= CHARGE_LOOKUP_ATTEMPTS; attempt++) {
            try {
                Optional<PaddleWebhookData> latest = unapplied(paddleClient.findLatestChargeTransaction(subscriptionId));
                if (latest.isPresent()) {
                    return latest;
                }
            } catch (RuntimeException ex) {
                log.warn("Looking up the charge on subscription {} failed: {}", subscriptionId, ex.getMessage());
            }
            if (attempt < CHARGE_LOOKUP_ATTEMPTS && !pause()) {
                break;
            }
        }
        return Optional.empty();
    }

    private Optional<PaddleWebhookData> unapplied(Optional<PaddleWebhookData> charge) {
        return charge.filter(c -> !installmentRepository.existsByPaddleTransactionId(c.id()));
    }

    private boolean pause() {
        try {
            Thread.sleep(lookupDelay);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
