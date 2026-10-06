package com.bridgepay.application.service;

import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreRequest;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import com.bridgepay.application.domain.DecisionSource;
import com.bridgepay.application.domain.IdempotencyKey;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.domain.MerchantPayout;
import com.bridgepay.application.domain.PayoutStatus;
import com.bridgepay.application.domain.OutboxEvent;
import com.bridgepay.application.dto.ApplicantApplicationResponse;
import com.bridgepay.application.dto.ApplicationCaseResponse;
import com.bridgepay.application.dto.ApplicationResponse;
import com.bridgepay.application.dto.CheckoutRequest;
import com.bridgepay.application.dto.CreditLimitResponse;
import com.bridgepay.application.dto.MerchantPayoutResponse;
import com.bridgepay.application.dto.MerchantSaleResponse;
import com.bridgepay.application.dto.ReviewDecisionRequest;
import com.bridgepay.application.event.ApplicationEvents;
import com.bridgepay.application.event.EventEnvelope;
import com.bridgepay.application.repository.CreditApplicationRepository;
import com.bridgepay.application.repository.IdempotencyKeyRepository;
import com.bridgepay.application.repository.MerchantPayoutRepository;
import com.bridgepay.application.repository.MerchantRepository;
import com.bridgepay.application.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class CreditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(CreditApplicationService.class);

    private static final int INSTALLMENT_COUNT = 4;
    private static final Set<ApplicationStatus> AWAITING_DECISION =
            Set.of(ApplicationStatus.PENDING, ApplicationStatus.MANUAL_REVIEW);

    private final CreditApplicationRepository applicationRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantPayoutRepository merchantPayoutRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CreditRiskClient creditRiskClient;
    private final ObjectMapper objectMapper;
    private final SpendingLimitService spendingLimitService;

    public CreditApplicationService(CreditApplicationRepository applicationRepository,
                                     MerchantRepository merchantRepository,
                                     MerchantPayoutRepository merchantPayoutRepository,
                                     IdempotencyKeyRepository idempotencyKeyRepository,
                                     OutboxEventRepository outboxEventRepository,
                                     CreditRiskClient creditRiskClient,
                                     ObjectMapper objectMapper,
                                     SpendingLimitService spendingLimitService) {
        this.applicationRepository = applicationRepository;
        this.merchantRepository = merchantRepository;
        this.merchantPayoutRepository = merchantPayoutRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.creditRiskClient = creditRiskClient;
        this.objectMapper = objectMapper;
        this.spendingLimitService = spendingLimitService;
    }

    @Transactional
    public ApplicationResponse checkout(UUID applicantId, String idempotencyKey, CheckoutRequest request) {
        var existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return readSnapshot(existing.get().getResponseSnapshot());
        }

        Merchant merchant = merchantRepository.findById(request.merchantId())
                .orElseThrow(() -> new NoSuchElementException("Merchant not found"));

        CreditLimitResponse spending = spendingLimitService.forApplicant(applicantId);
        // ponytail: two concurrent checkouts by one shopper can both pass this check; lock the applicant's
        // application rows (SELECT ... FOR UPDATE) here if that ever matters. Double-submits are covered by
        // the idempotency key above.
        if (spending.available() != null && request.amount().compareTo(spending.available()) > 0) {
            throw new OverLimitException(request.amount(), spending.available());
        }

        ScoreResult scoreResult = creditRiskClient.score(new ScoreRequest(
                applicantId, request.amount(), "general", Instant.now()));
        if (spending.limit() == null) {
            scoreResult = scoreResult.withCreditLimitUnavailable();
        }

        CreditApplication application = new CreditApplication(applicantId, merchant, request.amount());
        applicationRepository.save(application);

        finalizeDecision(application, merchant, scoreResult);
        if (application.getStatus() == ApplicationStatus.APPROVED
                || application.getStatus() == ApplicationStatus.DECLINED) {
            application.recordDecisionMaker(DecisionSource.MODEL, null, null);
        }

        ApplicationResponse response = toResponse(application);
        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, applicantId, writeSnapshot(response)));
        return response;
    }

    @Transactional
    public ApplicationResponse reviewDecision(UUID applicationId, ReviewDecisionRequest request, String reviewer) {
        CreditApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));

        if (application.getStatus() != ApplicationStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("Only applications in MANUAL_REVIEW can be reviewed");
        }

        // The model's score factors are the record of why this went to review; a human decision keeps them.
        ScoreResult carriedForwardScore = new ScoreResult(
                application.getRiskScore() != null ? application.getRiskScore() : 0.0,
                "APPROVE".equals(request.decision()) ? ScoreDecision.APPROVE : ScoreDecision.DECLINE,
                readScoreFactors(application.getScoreFactorsJson()));

        finalizeDecision(application, application.getMerchant(), carriedForwardScore);
        application.recordDecisionMaker(DecisionSource.OPS, reviewer, blankToNull(request.reviewerNote()));
        return toResponse(application);
    }

    private static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getForApplicant(UUID applicantId, UUID applicationId) {
        CreditApplication application = applicationRepository.findByIdAndApplicantId(applicationId, applicantId)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationResponse getForOps(UUID applicationId) {
        CreditApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));
        return toResponse(application);
    }

    @Transactional(readOnly = true)
    public ApplicationCaseResponse getCase(UUID applicationId) {
        CreditApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));
        Merchant merchant = application.getMerchant();

        ApplicationCaseResponse.Decision decision = AWAITING_DECISION.contains(application.getStatus())
                ? null
                : new ApplicationCaseResponse.Decision(
                        application.getDecisionSource() == null ? null : application.getDecisionSource().name(),
                        application.getDecidedBy(),
                        application.getReviewerNote());

        ApplicationCaseResponse.PayoutInfo payout = merchantPayoutRepository.findByApplicationId(applicationId)
                .map(p -> new ApplicationCaseResponse.PayoutInfo(p.getAmount(), p.getFeeAmount(),
                        p.getAmount().subtract(p.getFeeAmount()), p.getStatus().name(), p.getPaidAt()))
                .orElse(null);

        return new ApplicationCaseResponse(
                application.getId(),
                application.getApplicantId(),
                application.getAmount(),
                application.getStatus().name(),
                application.getRiskScore(),
                readScoreFactors(application.getScoreFactorsJson()),
                application.getInstallmentCount(),
                application.getInstallmentAmount(),
                application.getCreatedAt(),
                application.getDecisionAt(),
                decision,
                new ApplicationCaseResponse.MerchantInfo(merchant.getId(), merchant.getName(), merchant.getFeeRatePct()),
                payout);
    }

    @Transactional(readOnly = true)
    public Page<ApplicationResponse> listApplications(String status, Pageable pageable) {
        if ("ALL".equalsIgnoreCase(status)) {
            return applicationRepository.findByDemoFalse(pageable).map(this::toResponse);
        }
        return applicationRepository.findByStatusAndDemoFalse(ApplicationStatus.valueOf(status), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ApplicationResponse> listForApplicant(UUID applicantId, Pageable pageable) {
        return applicationRepository.findByApplicantIdOrderByCreatedAtDesc(applicantId, pageable).map(this::toResponse);
    }

    /** Ops: every application of one shopper, demo rows included (the queue's demo filter is for the queue). */
    @Transactional(readOnly = true)
    public Page<ApplicantApplicationResponse> listForApplicantOps(UUID applicantId, Pageable pageable) {
        return applicationRepository.findByApplicantIdOrderByCreatedAtDesc(applicantId, pageable)
                .map(a -> new ApplicantApplicationResponse(a.getId(), a.getMerchant().getId(), a.getMerchant().getName(),
                        a.getAmount(), a.getStatus().name(),
                        a.getDecisionSource() == null ? null : a.getDecisionSource().name(),
                        a.getDecidedBy(), a.getCreatedAt(), a.getDecisionAt()));
    }

    @Transactional(readOnly = true)
    public Page<MerchantPayoutResponse> listPayoutsForMerchant(UUID merchantId, Pageable pageable) {
        return merchantPayoutRepository.findByMerchantId(merchantId, pageable)
                .map(payout -> new MerchantPayoutResponse(
                        payout.getId(),
                        payout.getApplication().getId(),
                        payout.getAmount(),
                        payout.getFeeAmount(),
                        payout.getStatus().name(),
                        payout.getPaidAt()
                ));
    }

    @Transactional(readOnly = true)
    public Page<MerchantSaleResponse> listSalesForMerchant(UUID merchantId, String status, Pageable pageable) {
        Page<CreditApplication> page = "ALL".equalsIgnoreCase(status)
                ? applicationRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                : applicationRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                        merchantId, ApplicationStatus.valueOf(status), pageable);
        Map<UUID, BigDecimal> fees = merchantPayoutRepository
                .findByApplicationIdIn(page.getContent().stream().map(CreditApplication::getId).toList()).stream()
                .collect(Collectors.toMap(p -> p.getApplication().getId(), MerchantPayout::getFeeAmount));
        return page.map(application -> toSale(application, fees.get(application.getId())));
    }

    /** Queues a full refund; repayment-reconciliation does the Paddle side and answers with plan-refunded. */
    @Transactional
    public MerchantSaleResponse requestRefund(UUID merchantId, UUID applicationId) {
        CreditApplication application = applicationRepository.findByIdAndMerchantId(applicationId, merchantId)
                .orElseThrow(() -> new NoSuchElementException("Order not found"));
        application.requestRefund();
        writeOutboxEvent("applications.refund-requested", application.getId(), EventEnvelope.of(
                "application.refund-requested", application.getId(),
                new ApplicationEvents.RefundRequested(application.getId(), merchantId, application.getApplicantId())));
        BigDecimal fee = merchantPayoutRepository.findByApplicationId(applicationId)
                .map(MerchantPayout::getFeeAmount).orElse(null);
        return toSale(application, fee);
    }

    /** The shopper has their money back: the order is refunded and the merchant payout reversed (fee kept). */
    @Transactional
    public void recordRefund(UUID applicationId) {
        if (applicationId == null) {
            log.warn("plan-refunded without applicationId, skipping");
            return;
        }
        applicationRepository.findById(applicationId).ifPresentOrElse(application -> {
            if (!application.markRefunded()) {
                log.warn("plan-refunded for application {} in status {}, ignoring", applicationId, application.getStatus());
                return;
            }
            merchantPayoutRepository.findByApplicationId(applicationId).ifPresent(MerchantPayout::reverse);
        }, () -> log.warn("No application {} on plan-refunded, skipping", applicationId));
    }

    private static MerchantSaleResponse toSale(CreditApplication application, BigDecimal feeAmount) {
        return new MerchantSaleResponse(
                application.getId(),
                application.getCreatedAt(),
                application.getAmount(),
                application.getStatus().name(),
                application.getInstallmentCount(),
                application.getInstallmentAmount(),
                application.getDecisionAt(),
                feeAmount);
    }

    /**
     * Installment 1 confirms the order and pays the merchant; every installment frees up some of the
     * shopper's spending limit. Safe to call repeatedly.
     */
    @Transactional
    public void recordInstallmentPaid(UUID applicationId, int sequenceNumber) {
        if (applicationId == null) {
            log.warn("installment-paid without applicationId (published before that field existed), skipping");
            return;
        }
        merchantPayoutRepository.findByApplicationId(applicationId).ifPresentOrElse(MerchantPayout::markPaid,
                () -> log.warn("No payout for application {} on installment-paid, skipping", applicationId));
        applicationRepository.findById(applicationId)
                .ifPresent(application -> application.recordInstallmentPaid(sequenceNumber));
    }

    @Transactional
    public void completePlan(UUID applicationId) {
        moveFromApproved(applicationId, CreditApplication::complete, "plan-completed");
    }

    /** A default never claws back a merchant payout - BridgePay carries that risk. */
    @Transactional
    public void defaultPlan(UUID applicationId) {
        moveFromApproved(applicationId, CreditApplication::markDefaulted, "plan-defaulted");
    }

    private void moveFromApproved(UUID applicationId, Predicate<CreditApplication> move, String event) {
        if (applicationId == null) {
            log.warn("{} without applicationId, skipping", event);
            return;
        }
        applicationRepository.findById(applicationId).ifPresentOrElse(application -> {
            if (!move.test(application)) {
                log.warn("{} for application {} in status {}, ignoring", event, applicationId, application.getStatus());
            }
        }, () -> log.warn("No application {} on {}, skipping", applicationId, event));
    }

    /** The first installment was never paid: the order is off. A payout that was already paid is never reversed. */
    @Transactional
    public void cancelUnpaidApplication(UUID applicationId) {
        Optional<MerchantPayout> payout = merchantPayoutRepository.findByApplicationId(applicationId);
        if (payout.isPresent() && payout.get().getStatus() == PayoutStatus.PAID) {
            log.warn("plan-cancelled for application {} whose payout is already paid, ignoring", applicationId);
            return;
        }
        applicationRepository.findById(applicationId).ifPresentOrElse(CreditApplication::cancel,
                () -> log.warn("No application {} on plan-cancelled, skipping", applicationId));
        payout.ifPresent(MerchantPayout::cancel);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * The single place both the automated checkout path and the ops manual
     * review path terminate - status update, installment/payout creation, and
     * the outbox event write all happen here exactly once, regardless of
     * which path triggered the decision.
     */
    private void finalizeDecision(CreditApplication application, Merchant merchant, ScoreResult scoreResult) {
        String scoreFactorsJson = writeSnapshot(scoreResult.scoreFactors());

        switch (scoreResult.decision()) {
            case APPROVE -> {
                BigDecimal installmentAmount = application.getAmount()
                        .divide(BigDecimal.valueOf(INSTALLMENT_COUNT), 2, RoundingMode.HALF_UP);
                application.applyDecision(ApplicationStatus.APPROVED, scoreResult.riskScore(), scoreFactorsJson,
                        INSTALLMENT_COUNT, installmentAmount);

                BigDecimal feeAmount = application.getAmount()
                        .multiply(merchant.getFeeRatePct())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                merchantPayoutRepository.save(new MerchantPayout(application, merchant, application.getAmount(), feeAmount));

                writeOutboxEvent("applications.approved", application.getId(), EventEnvelope.of(
                        "application.approved", application.getId(),
                        new ApplicationEvents.Approved(application.getApplicantId(), merchant.getId(),
                                application.getAmount(), INSTALLMENT_COUNT, installmentAmount)));
            }
            case MANUAL_REVIEW -> {
                application.applyDecision(ApplicationStatus.MANUAL_REVIEW, scoreResult.riskScore(), scoreFactorsJson,
                        null, null);
                writeOutboxEvent("applications.manual-review", application.getId(), EventEnvelope.of(
                        "application.manual-review", application.getId(),
                        new ApplicationEvents.ManualReview(application.getApplicantId(), scoreResult.riskScore())));
            }
            case DECLINE -> {
                application.applyDecision(ApplicationStatus.DECLINED, scoreResult.riskScore(), scoreFactorsJson,
                        null, null);
                writeOutboxEvent("applications.declined", application.getId(), EventEnvelope.of(
                        "application.declined", application.getId(),
                        new ApplicationEvents.Declined(application.getApplicantId(), scoreResult.riskScore())));
            }
        }
    }

    private void writeOutboxEvent(String topic, UUID partitionKey, EventEnvelope<?> envelope) {
        outboxEventRepository.save(new OutboxEvent(topic, partitionKey.toString(), writeSnapshot(envelope)));
    }

    private ApplicationResponse toResponse(CreditApplication application) {
        return new ApplicationResponse(
                application.getId(),
                application.getApplicantId(),
                application.getMerchant().getId(),
                application.getAmount(),
                application.getStatus().name(),
                application.getRiskScore(),
                readScoreFactors(application.getScoreFactorsJson()),
                application.getInstallmentCount(),
                application.getInstallmentAmount(),
                application.getDecisionAt()
        );
    }

    private List<ScoreResult.ScoreFactor> readScoreFactors(String scoreFactorsJson) {
        if (scoreFactorsJson == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(scoreFactorsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ScoreResult.ScoreFactor.class));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize stored score factors", ex);
        }
    }

    private String writeSnapshot(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize value for storage", ex);
        }
    }

    private ApplicationResponse readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, ApplicationResponse.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to deserialize cached idempotent response", ex);
        }
    }
}
