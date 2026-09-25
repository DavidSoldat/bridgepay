package com.bridgepay.repayment.repository;

import com.bridgepay.repayment.domain.FailedEvent;
import com.bridgepay.repayment.domain.FailedEventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {
    Page<FailedEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<FailedEvent> findByStatusOrderByCreatedAtDesc(FailedEventStatus status, Pageable pageable);

    /**
     * Atomically moves a FAILED row to RETRYING; returns 0 if another retry
     * already holds it (or it isn't FAILED). This - not @Version, which is only
     * checked after the replay has already called Paddle - is what stops two
     * concurrent retries from both creating Paddle transactions.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update FailedEvent e set e.status = com.bridgepay.repayment.domain.FailedEventStatus.RETRYING, "
            + "e.version = e.version + 1 "
            + "where e.id = :id and e.status = com.bridgepay.repayment.domain.FailedEventStatus.FAILED")
    int claimForRetry(@Param("id") UUID id);
}
