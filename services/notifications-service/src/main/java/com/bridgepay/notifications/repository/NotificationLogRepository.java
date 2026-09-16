package com.bridgepay.notifications.repository;

import com.bridgepay.notifications.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
}
