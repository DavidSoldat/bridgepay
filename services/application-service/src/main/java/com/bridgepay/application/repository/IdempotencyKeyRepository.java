package com.bridgepay.application.repository;

import com.bridgepay.application.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, java.util.UUID> {
    Optional<IdempotencyKey> findByIdempotencyKey(String idempotencyKey);
}
