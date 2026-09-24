package com.bridgepay.application.repository;

import com.bridgepay.application.domain.MerchantPayout;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MerchantPayoutRepository extends JpaRepository<MerchantPayout, UUID> {

    Page<MerchantPayout> findByMerchantId(UUID merchantId, Pageable pageable);

    @Query("""
            select new com.bridgepay.application.repository.MerchantPayoutTotals(sum(p.amount), sum(p.feeAmount))
            from MerchantPayout p
            where p.merchant.id = :merchantId""")
    MerchantPayoutTotals totalsForMerchant(@Param("merchantId") UUID merchantId);
}
