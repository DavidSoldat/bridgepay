package com.bridgepay.repayment.service;

import com.bridgepay.repayment.client.ApplicantClient;
import com.bridgepay.repayment.client.ApplicantProfile;
import com.bridgepay.repayment.client.PaddleClient;
import com.bridgepay.repayment.client.PaddleTransactionResult;
import com.bridgepay.repayment.domain.Installment;
import com.bridgepay.repayment.domain.RepaymentPlan;
import com.bridgepay.repayment.event.ApplicationEvents;
import com.bridgepay.repayment.repository.InstallmentRepository;
import com.bridgepay.repayment.repository.RepaymentPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
public class RepaymentPlanService {

    private static final Logger log = LoggerFactory.getLogger(RepaymentPlanService.class);

    private final RepaymentPlanRepository repaymentPlanRepository;
    private final InstallmentRepository installmentRepository;
    private final ApplicantClient applicantClient;
    private final PaddleClient paddleClient;

    public RepaymentPlanService(RepaymentPlanRepository repaymentPlanRepository,
                                 InstallmentRepository installmentRepository,
                                 ApplicantClient applicantClient,
                                 PaddleClient paddleClient) {
        this.repaymentPlanRepository = repaymentPlanRepository;
        this.installmentRepository = installmentRepository;
        this.applicantClient = applicantClient;
        this.paddleClient = paddleClient;
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
}
