package com.bridgepay.notifications.dto;

import java.util.List;

public record NotificationFeedPage(List<NotificationFeedItem> items, long unreadCount, int page, boolean hasMore) {
}
