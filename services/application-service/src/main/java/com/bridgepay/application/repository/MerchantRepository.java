package com.bridgepay.application.repository;

import com.bridgepay.application.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
}
