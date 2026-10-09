package com.bridgepay.application.repository;

import com.bridgepay.application.domain.MerchantPayout;
import com.bridgepay.application.domain.PayoutStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantPayoutRepository extends JpaRepository<MerchantPayout, UUID> {

    // Newest first: the demo seed holds months of payouts, so a fresh one must lead page 1.
    Page<MerchantPayout> findByMerchantIdOrderByCreatedAtDescIdDesc(UUID merchantId, Pageable pageable);

    Optional<MerchantPayout> findByApplicationId(UUID applicationId);

    List<MerchantPayout> findByApplicationIdIn(Collection<UUID> applicationIds);

}
