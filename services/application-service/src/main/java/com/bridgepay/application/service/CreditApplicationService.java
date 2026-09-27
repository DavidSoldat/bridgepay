package com.bridgepay.application.service;

import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreRequest;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import com.bridgepay.application.domain.IdempotencyKey;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.domain.MerchantPayout;
import com.bridgepay.application.domain.PayoutStatus;
import com.bridgepay.application.domain.OutboxEvent;
import com.bridgepay.application.dto.ApplicationResponse;
import com.bridgepay.application.dto.CheckoutRequest;
import com.bridgepay.application.dto.MerchantPayoutResponse;
import com.bridgepay.application.dto.MerchantSaleResponse;
import com.bridgepay.application.dto.MerchantSummaryResponse;
import com.bridgepay.application.dto.ReviewDecisionRequest;
import com.bridgepay.application.event.ApplicationEvents;
import com.bridgepay.application.event.EventEnvelope;
import com.bridgepay.application.repository.CreditApplicationRepository;
import com.bridgepay.application.repository.IdempotencyKeyRepository;
import com.bridgepay.application.repository.MerchantPayoutRepository;
import com.bridgepay.application.repository.MerchantPayoutTotals;
import com.bridgepay.application.repository.MerchantStatusTotals;
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
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class CreditApplicationService {

    private static final Logger log = LoggerFactory.getLogger(CreditApplicationService.class);

    private static final int INSTALLMENT_COUNT = 4;

    private final CreditApplicationRepository applicationRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantPayoutRepository merchantPayoutRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final CreditRiskClient creditRiskClient;
    private final ObjectMapper objectMapper;

    public CreditApplicationService(CreditApplicationRepository applicationRepository,
                                     MerchantRepository merchantRepository,
                                     MerchantPayoutRepository merchantPayoutRepository,
                                     IdempotencyKeyRepository idempotencyKeyRepository,
                                     OutboxEventRepository outboxEventRepository,
                                     CreditRiskClient creditRiskClient,
                                     ObjectMapper objectMapper) {
        this.applicationRepository = applicationRepository;
        this.merchantRepository = merchantRepository;
        this.merchantPayoutRepository = merchantPayoutRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.creditRiskClient = creditRiskClient;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ApplicationResponse checkout(UUID applicantId, String idempotencyKey, CheckoutRequest request) {
        var existing = idempotencyKeyRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return readSnapshot(existing.get().getResponseSnapshot());
        }

        Merchant merchant = merchantRepository.findById(request.merchantId())
                .orElseThrow(() -> new NoSuchElementException("Merchant not found"));

        ScoreResult scoreResult = creditRiskClient.score(new ScoreRequest(
                applicantId, request.amount(), "general", Instant.now()));

        CreditApplication application = new CreditApplication(applicantId, merchant, request.amount());
        applicationRepository.save(application);

        finalizeDecision(application, merchant, scoreResult);

        ApplicationResponse response = toResponse(application);
        idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, applicantId, writeSnapshot(response)));
        return response;
    }

    @Transactional
    public ApplicationResponse reviewDecision(UUID applicationId, ReviewDecisionRequest request) {
        CreditApplication application = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new NoSuchElementException("Application not found"));

        if (application.getStatus() != ApplicationStatus.MANUAL_REVIEW) {
            throw new IllegalStateException("Only applications in MANUAL_REVIEW can be reviewed");
        }

        ScoreResult carriedForwardScore = new ScoreResult(
                application.getRiskScore() != null ? application.getRiskScore() : 0.0,
                "APPROVE".equals(request.decision()) ? ScoreDecision.APPROVE : ScoreDecision.DECLINE,
                List.of());

        finalizeDecision(application, application.getMerchant(), carriedForwardScore);
        return toResponse(application);
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
    public Page<ApplicationResponse> listApplications(String status, Pageable pageable) {
        if ("ALL".equalsIgnoreCase(status)) {
            return applicationRepository.findAll(pageable).map(this::toResponse);
        }
        return applicationRepository.findByStatus(ApplicationStatus.valueOf(status), pageable)
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ApplicationResponse> listForApplicant(UUID applicantId, Pageable pageable) {
        return applicationRepository.findByApplicantIdOrderByCreatedAtDesc(applicantId, pageable).map(this::toResponse);
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
        return page.map(application -> new MerchantSaleResponse(
                application.getId(),
                application.getCreatedAt(),
                application.getAmount(),
                application.getStatus().name(),
                application.getInstallmentCount(),
                application.getInstallmentAmount(),
                application.getDecisionAt()
        ));
    }

    @Transactional(readOnly = true)
    public MerchantSummaryResponse summaryForMerchant(UUID merchantId) {
        long total = 0;
        long approved = 0;
        long inReview = 0;
        long declined = 0;
        BigDecimal approvedVolume = BigDecimal.ZERO;
        for (MerchantStatusTotals totals : applicationRepository.totalsByStatusForMerchant(merchantId)) {
            total += totals.count();
            switch (totals.status()) {
                case APPROVED -> {
                    approved = totals.count();
                    approvedVolume = totals.volume();
                }
                case MANUAL_REVIEW -> inReview = totals.count();
                case DECLINED -> declined = totals.count();
                // COMPLETED/DEFAULTED/CANCELLED only count toward the total (CANCELLED = approved but the first
                // payment was never made). ponytail: COMPLETED/DEFAULTED aren't set here yet; fold them into
                // approved count/volume once repayment status flows back here.
                default -> { }
            }
        }
        Double approvalRate = approved + declined == 0 ? null : (double) approved / (approved + declined);

        MerchantPayoutTotals paid = merchantPayoutRepository.totalsForMerchant(merchantId, PayoutStatus.PAID);
        MerchantPayoutTotals pending = merchantPayoutRepository.totalsForMerchant(merchantId, PayoutStatus.PENDING);
        BigDecimal fees = orZero(paid.fees());
        BigDecimal pendingNet = orZero(pending.gross()).subtract(orZero(pending.fees()));

        return new MerchantSummaryResponse(total, approved, inReview, declined, approvalRate,
                approvedVolume, fees, orZero(paid.gross()).subtract(fees), pendingNet);
    }

    /** Installment 1 cleared, so the order is confirmed and the merchant is paid. Safe to call repeatedly. */
    @Transactional
    public void recordFirstPaymentCleared(UUID applicationId) {
        if (applicationId == null) {
            log.warn("installment-paid without applicationId (published before that field existed), skipping");
            return;
        }
        merchantPayoutRepository.findByApplicationId(applicationId).ifPresentOrElse(MerchantPayout::markPaid,
                () -> log.warn("No payout for application {} on installment-paid, skipping", applicationId));
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
