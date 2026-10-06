package com.bridgepay.notifications.web;

import com.bridgepay.notifications.dto.NotificationFeedPage;
import com.bridgepay.notifications.service.NotificationFeedService;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The signed-in shopper's own notifications (the JWT subject is the applicant id), and ops' read-only view of any shopper's. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationFeedController {

    private final NotificationFeedService feedService;

    public NotificationFeedController(NotificationFeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping
    public NotificationFeedPage feed(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return feedService.page(UUID.fromString(jwt.getSubject()), page, size);
    }

    /** Ops, read-only: page() never touches notification_reads, so the shopper's unread state is unchanged. */
    @GetMapping("/applicants/{applicantId}")
    @PreAuthorize("hasRole('OPS')")
    public NotificationFeedPage feedForOps(@PathVariable UUID applicantId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return feedService.page(applicantId, page, size);
    }

    @PostMapping("/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        feedService.markAllRead(UUID.fromString(jwt.getSubject()));
    }
}
