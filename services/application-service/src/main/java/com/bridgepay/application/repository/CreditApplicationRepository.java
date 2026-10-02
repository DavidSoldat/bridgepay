package com.bridgepay.application.repository;

import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {

    /** Ops queue: seeded demo orders (is_demo) have no shopper or plan behind them, so they stay out. */
    Page<CreditApplication> findByDemoFalse(Pageable pageable);

    Page<CreditApplication> findByStatusAndDemoFalse(ApplicationStatus status, Pageable pageable);

    Optional<CreditApplication> findByIdAndApplicantId(UUID id, UUID applicantId);

    List<CreditApplication> findByApplicantIdAndStatusIn(UUID applicantId, Collection<ApplicationStatus> statuses);

    Page<CreditApplication> findByApplicantIdOrderByCreatedAtDesc(UUID applicantId, Pageable pageable);

    Page<CreditApplication> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<CreditApplication> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, ApplicationStatus status,
                                                                          Pageable pageable);

}
