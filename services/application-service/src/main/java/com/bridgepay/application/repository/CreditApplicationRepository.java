package com.bridgepay.application.repository;

import com.bridgepay.application.domain.ApplicationStatus;
import com.bridgepay.application.domain.CreditApplication;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditApplicationRepository extends JpaRepository<CreditApplication, UUID> {

    Page<CreditApplication> findByStatus(ApplicationStatus status, Pageable pageable);

    Optional<CreditApplication> findByIdAndApplicantId(UUID id, UUID applicantId);

    Page<CreditApplication> findByApplicantIdOrderByCreatedAtDesc(UUID applicantId, Pageable pageable);

    Page<CreditApplication> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<CreditApplication> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, ApplicationStatus status,
                                                                          Pageable pageable);

    @Query("""
            select new com.bridgepay.application.repository.MerchantStatusTotals(a.status, count(a), sum(a.amount))
            from CreditApplication a
            where a.merchant.id = :merchantId
            group by a.status""")
    List<MerchantStatusTotals> totalsByStatusForMerchant(@Param("merchantId") UUID merchantId);
}
