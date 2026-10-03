package com.bridgepay.notifications.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationFeedItem(UUID id, String type, String title, String body, UUID applicationId,
                                   Instant createdAt, boolean unread) {
}
