package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findTop50ByPublishedFalseOrderByCreatedAtAsc();
}
