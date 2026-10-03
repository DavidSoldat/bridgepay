package com.bridgepay.notifications.service;

import com.bridgepay.notifications.dto.NotificationFeedItem;
import com.bridgepay.notifications.dto.NotificationFeedPage;
import com.bridgepay.notifications.repository.NotificationFeedRepository;
import com.bridgepay.notifications.repository.NotificationFeedRepository.FeedRow;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationFeedService {

    static final int MAX_PAGE_SIZE = 50;

    private final NotificationFeedRepository repository;

    public NotificationFeedService(NotificationFeedRepository repository) {
        this.repository = repository;
    }

    public NotificationFeedPage page(UUID applicantId, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be 0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Instant readThrough = repository.readThrough(applicantId).orElse(Instant.EPOCH);
        List<FeedRow> rows = repository.groups(applicantId, size + 1, (long) page * size);
        List<NotificationFeedItem> items = rows.stream().limit(size).map(row -> toItem(row, readThrough)).toList();
        return new NotificationFeedPage(items, repository.unreadGroups(applicantId, readThrough), page, rows.size() > size);
    }

    public void markAllRead(UUID applicantId) {
        repository.markRead(applicantId, Instant.now());
    }

    private static NotificationFeedItem toItem(FeedRow row, Instant readThrough) {
        boolean merged = row.count() > 1;
        String title = merged ? "Payments " + row.minSequence() + "–" + row.maxSequence() + " received" : row.title();
        String body = merged ? NotificationCopy.money(row.totalAmount()) : row.body();
        return new NotificationFeedItem(row.id(), row.type(), title, body, row.applicationId(), row.createdAt(),
                row.createdAt().isAfter(readThrough));
    }
}
