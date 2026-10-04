package com.bridgepay.application.service;

import com.bridgepay.application.client.CreditLimitClient;
import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import com.bridgepay.application.dto.CreditLimitResponse;
import com.bridgepay.application.repository.CreditApplicationRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;

/** Limit from the Credit Risk Engine; what's used of it from this service's own applications. */
@Service
public class SpendingLimitService {

    private static final EnumSet<ApplicationStatus> HOLDING_LIMIT =
            EnumSet.of(ApplicationStatus.APPROVED, ApplicationStatus.MANUAL_REVIEW,
                    ApplicationStatus.REFUND_PENDING);

    private final CreditLimitClient creditLimitClient;
    private final CreditApplicationRepository applicationRepository;

    public SpendingLimitService(CreditLimitClient creditLimitClient, CreditApplicationRepository applicationRepository) {
        this.creditLimitClient = creditLimitClient;
        this.applicationRepository = applicationRepository;
    }

    /**
     * Deliberately not @Transactional: the engine call is HTTP, and a database connection held across it
     * would let a slow engine drain the pool. The query runs in its own read transaction; checkout still
     * calls this inside its own transaction, as it always held one across the score call.
     */
    public CreditLimitResponse forApplicant(UUID applicantId) {
        BigDecimal outstanding = applicationRepository.findByApplicantIdAndStatusIn(applicantId, HOLDING_LIMIT).stream()
                .map(CreditApplication::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2);
        return creditLimitClient.creditLimit(applicantId)
                .map(l -> new CreditLimitResponse(l.limit(), outstanding,
                        l.limit().subtract(outstanding).max(BigDecimal.ZERO).setScale(2), l.band()))
                .orElseGet(() -> new CreditLimitResponse(null, outstanding, null, null));
    }
}
