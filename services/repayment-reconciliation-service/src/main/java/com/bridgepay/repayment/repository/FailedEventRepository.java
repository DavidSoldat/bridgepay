package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {
    Page<FailedEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<FailedEvent> findByStatusOrderByCreatedAtDesc(FailedEventStatus status, Pageable pageable);
}
