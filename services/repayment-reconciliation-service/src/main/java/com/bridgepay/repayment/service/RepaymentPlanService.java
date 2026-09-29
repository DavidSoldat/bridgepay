package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.client.PaddleWebhookData;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.InstallmentStatus;
import com.bridgepay.repayment.domain.PlanStatus;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.dto.InstallmentResponse;
import com.bridgepay.repayment.dto.RepaymentPlanResponse;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class RepaymentPlanService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentPlanService.class);

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final InstallmentRepository installmentRepository;
    private final ApplicantClient applicantClient;
    private final PaddleClient paddleClient;
    private final PaddleWebhookService paddleWebhookService;

    public RepaymentPlanService(RepaymentPlanRepository repaymentPlanRepository,
                                 InstallmentRepository installmentRepository,
                                 ApplicantClient applicantClient,
                                 PaddleClient paddleClient,
                                 PaddleWebhookService paddleWebhookService) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.installmentRepository = installmentRepository;
        this.applicantClient = applicantClient;
        this.paddleClient = paddleClient;
        this.paddleWebhookService = paddleWebhookService;
    }

    /**
     * A pre-check (not just the unique constraint) is required here, unlike a
     * pure notification log insert: Paddle customer/transaction creation are
     * real external side effects, so a redelivery must never repeat them.
     * The unique constraint on application_id remains as a backstop against
     * the (rare) race of two redeliveries processed concurrently.
     */
    @Transactional
    public void createPlanFromApprovedApplication(UUID applicationId, ApplicationEvents.Approved payload) {
        if (repaymentPlanRepository.findByApplicationId(applicationId).isPresent()) {
            log.debug("Repayment plan already exists for application {}, skipping redelivery", applicationId);
            return;
        }

        ApplicantProfile applicant = applicantClient.fetchProfile(payload.applicantId());
        String paddleCustomerId = applicant.paddleCustomerId();
        if (paddleCustomerId == null) {
            paddleCustomerId = paddleClient.findOrCreateCustomer(applicant.email(), applicant.fullName());
            applicantClient.setPaddleCustomerId(applicant.id(), paddleCustomerId);
        }

        PaddleTransactionResult transaction = paddleClient.createInstallmentTransaction(
                paddleCustomerId, payload.installmentAmount());

        RepaymentPlan plan = new RepaymentPlan(applicationId, payload.applicantId(), paddleCustomerId,
                transaction.transactionId(), payload.amount(), payload.installmentCount(), payload.installmentAmount());

        try {
            repaymentPlanRepository.saveAndFlush(plan);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent redelivery for application {}, skipping", applicationId);
            return;
        }

        LocalDate firstDueDate = LocalDate.now();
        for (int sequence = 1; sequence <= payload.installmentCount(); sequence++) {
            installmentRepository.save(new Installment(plan, sequence,
                    firstDueDate.plusWeeks(sequence - 1L), payload.installmentAmount()));
        }

        log.info("Created repayment plan {} for application {} - complete the sandbox checkout at {}",
                plan.getId(), applicationId, transaction.checkoutUrl());
    }

    // Not @Transactional: reconciling calls Paddle, and the webhook handling it replays runs its own transaction.
    public RepaymentPlanResponse getForApplicant(UUID applicationId, UUID applicantId) {
        RepaymentPlan plan = findPlan(applicationId);
        if (!plan.getApplicantId().equals(applicantId)) {
            throw new AccessDeniedException("Repayment plan does not belong to this applicant");
        }
        return reconciled(plan);
    }

    /** Ops case file: any plan. Shoppers go through getForApplicant's owner check. */
    public RepaymentPlanResponse getForOps(UUID applicationId) {
        return reconciled(findPlan(applicationId));
    }

    /**
     * A lost or late transaction.completed webhook (e.g. Paddle can't reach a local cluster) would leave the
     * shopper on "Action required" after paying. While installment 1 still looks unpaid, ask Paddle and replay
     * the webhook's own handling if it has completed. Paddle trouble never fails the read.
     */
    private RepaymentPlanResponse reconciled(RepaymentPlan plan) {
        RepaymentPlanResponse response = toResponse(plan);
        if (response.checkoutTransactionId() == null) {
            return response;
        }
        try {
            Optional<PaddleWebhookData> completed = paddleClient.findCompletedTransaction(response.checkoutTransactionId());
            if (completed.isEmpty()) {
                return response;
            }
            log.info("Paddle reports first payment {} completed for plan {}, applying it on read",
                    response.checkoutTransactionId(), plan.getId());
            paddleWebhookService.handle("transaction.completed", completed.get());
        } catch (RuntimeException ex) {
            log.warn("Could not reconcile first payment for plan {} with Paddle: {}", plan.getId(), ex.getMessage());
            return response;
        }
        return toResponse(findPlan(plan.getApplicationId()));
    }

    private RepaymentPlan findPlan(UUID applicationId) {
        return repaymentPlanRepository.findByApplicationId(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Repayment plan not found"));
    }

    private RepaymentPlanResponse toResponse(RepaymentPlan plan) {
        List<Installment> installments = installmentRepository.findByRepaymentPlanOrderBySequenceNumberAsc(plan);
        boolean firstInstallmentUnpaid = !installments.isEmpty()
                && installments.get(0).getStatus() != InstallmentStatus.PAID;
        String checkoutTransactionId = plan.getStatus() == PlanStatus.ACTIVE && firstInstallmentUnpaid
                ? plan.getPaddleInitialTransactionId() : null;
        return new RepaymentPlanResponse(
                plan.getId(), plan.getApplicationId(), plan.getStatus().name(),
                plan.getTotalAmount(), plan.getInstallmentCount(), plan.getInstallmentAmount(),
                installments.stream().map(this::toInstallmentResponse).toList(),
                checkoutTransactionId);
    }

    private InstallmentResponse toInstallmentResponse(Installment installment) {
        return new InstallmentResponse(installment.getSequenceNumber(), installment.getDueDate(),
                installment.getAmount(), installment.getStatus().name(), installment.getPaidAt());
    }
}
