package com.bridgepay.application.repository;

import com.bridgepay.application.domain.MerchantPayout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantPayoutRepository extends JpaRepository<MerchantPayout, UUID> {
}
