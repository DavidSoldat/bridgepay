package com.bridgepay.application.service;

import com.bridgepay.application.client.CreditRiskClient;
import com.bridgepay.application.client.ScoreDecision;
import com.bridgepay.application.client.ScoreResult;
import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import com.bridgepay.application.domain.IdempotencyKey;
import com.bridgepay.application.domain.Merchant;
import com.bridgepay.application.dto.ApplicationResponse;
import com.bridgepay.application.dto.CheckoutRequest;
import com.bridgepay.application.dto.ReviewDecisionRequest;
import com.bridgepay.application.repository.CreditApplicationRepository;
import com.bridgepay.application.repository.IdempotencyKeyRepository;
import com.bridgepay.application.repository.MerchantPayoutRepository;
import com.bridgepay.application.repository.MerchantRepository;
import com.bridgepay.application.repository.OutboxEventRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreditApplicationServiceTest {

    @Mock
    private CreditApplicationRepository applicationRepository;
    @Mock
    private MerchantRepository merchantRepository;
    @Mock
    private MerchantPayoutRepository merchantPayoutRepository;
    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private CreditRiskClient creditRiskClient;

    private ObjectMapper objectMapper;
    private CreditApplicationService service;

    private final UUID applicantId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        service = new CreditApplicationService(applicationRepository, merchantRepository, merchantPayoutRepository,
                idempotencyKeyRepository, outboxEventRepository, creditRiskClient, objectMapper);
        merchant = new Merchant("Test Merchant", new BigDecimal("3.50"));
    }

    @Test
    void checkout_approves_andCreatesPayoutAndOutboxEvent_whenScoreIsApprove() {
        when(idempotencyKeyRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(creditRiskClient.score(any())).thenReturn(new ScoreResult(0.15, ScoreDecision.APPROVE, List.of()));

        var response = service.checkout(applicantId, "key-1", new CheckoutRequest(merchantId, new BigDecimal("200.00")));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.installmentCount()).isEqualTo(4);
        assertThat(response.installmentAmount()).isEqualByComparingTo("50.00");
        verify(merchantPayoutRepository).save(any());
        verify(outboxEventRepository).save(any());
        verify(idempotencyKeyRepository).save(any(IdempotencyKey.class));
    }

    @Test
    void checkout_returnsCachedResponse_whenIdempotencyKeyAlreadyUsed() {
        String cachedJson = "{\"applicationId\":\"" + UUID.randomUUID()
                + "\",\"status\":\"APPROVED\",\"installmentCount\":4,\"installmentAmount\":50.00,\"decisionAt\":null}";
        IdempotencyKey existing = new IdempotencyKey("key-1", applicantId, cachedJson);
        when(idempotencyKeyRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        var response = service.checkout(applicantId, "key-1", new CheckoutRequest(merchantId, new BigDecimal("200.00")));

        assertThat(response.status()).isEqualTo("APPROVED");
        verify(creditRiskClient, never()).score(any());
        verify(merchantRepository, never()).findById(any());
    }

    @Test
    void checkout_declines_withoutCreatingPayout_whenScoreIsDecline() {
        when(idempotencyKeyRepository.findByIdempotencyKey("key-2")).thenReturn(Optional.empty());
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(creditRiskClient.score(any())).thenReturn(new ScoreResult(0.9, ScoreDecision.DECLINE, List.of()));

        var response = service.checkout(applicantId, "key-2", new CheckoutRequest(merchantId, new BigDecimal("200.00")));

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.installmentCount()).isNull();
        verify(merchantPayoutRepository, never()).save(any());
    }

    @Test
    void reviewDecision_throws_whenApplicationIsNotInManualReview() {
        CreditApplication approved = new CreditApplication(applicantId, merchant, new BigDecimal("200.00"));
        approved.applyDecision(ApplicationStatus.APPROVED, 0.1, "[]", 4, new BigDecimal("50.00"));
        UUID id = approved.getId();
        when(applicationRepository.findById(id)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.reviewDecision(id, new ReviewDecisionRequest("APPROVE", "note")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MANUAL_REVIEW");
    }

    @Test
    void reviewDecision_approvesAndCreatesPayout_whenOpsApprovesAManualReviewApplication() {
        CreditApplication pending = new CreditApplication(applicantId, merchant, new BigDecimal("100.00"));
        pending.applyDecision(ApplicationStatus.MANUAL_REVIEW, 0.5, "[]", null, null);
        UUID id = pending.getId();
        when(applicationRepository.findById(id)).thenReturn(Optional.of(pending));

        var response = service.reviewDecision(id, new ReviewDecisionRequest("APPROVE", "looks fine"));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.installmentAmount()).isEqualByComparingTo("25.00");
        verify(merchantPayoutRepository).save(any());
    }

    @Test
    void listApplications_filtersByGivenStatus_whenStatusIsNotAll() {
        Pageable pageable = PageRequest.of(0, 20);
        CreditApplication declined = new CreditApplication(applicantId, merchant, new BigDecimal("100.00"));
        declined.applyDecision(ApplicationStatus.DECLINED, 0.9, "[]", null, null);
        when(applicationRepository.findByStatus(ApplicationStatus.DECLINED, pageable))
                .thenReturn(new PageImpl<>(List.of(declined)));

        Page<ApplicationResponse> result = service.listApplications("DECLINED", pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo("DECLINED");
        verify(applicationRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void listApplications_returnsEveryStatus_whenStatusIsAll() {
        Pageable pageable = PageRequest.of(0, 20);
        CreditApplication approved = new CreditApplication(applicantId, merchant, new BigDecimal("100.00"));
        approved.applyDecision(ApplicationStatus.APPROVED, 0.1, "[]", 4, new BigDecimal("25.00"));
        when(applicationRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(approved)));

        Page<ApplicationResponse> result = service.listApplications("ALL", pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(applicationRepository, never()).findByStatus(any(), any());
    }

    @Test
    void listApplications_throwsIllegalArgument_whenStatusIsUnknown() {
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.listApplications("NOT_A_STATUS", pageable))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listForApplicant_returnsOnlyThatApplicantsApplications() {
        Pageable pageable = PageRequest.of(0, 20);
        CreditApplication mine = new CreditApplication(applicantId, merchant, new BigDecimal("100.00"));
        mine.applyDecision(ApplicationStatus.APPROVED, 0.1, "[]", 4, new BigDecimal("25.00"));
        when(applicationRepository.findByApplicantId(applicantId, pageable))
                .thenReturn(new PageImpl<>(List.of(mine)));

        Page<ApplicationResponse> result = service.listForApplicant(applicantId, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).applicantId()).isEqualTo(applicantId);
    }
}
