package com.bridgepay.notifications.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** A notification ready to record: shopper-facing copy plus what the feed needs to link and group it. */
public record NotificationDraft(UUID applicantId, NotificationType type, String title, String body,
                                UUID applicationId, String groupKey, Integer sequenceNumber, BigDecimal amount) {

    public static NotificationDraft simple(UUID applicantId, NotificationType type, String title, String body,
                                           UUID applicationId) {
        return new NotificationDraft(applicantId, type, title, body, applicationId, null, null, null);
    }
}
