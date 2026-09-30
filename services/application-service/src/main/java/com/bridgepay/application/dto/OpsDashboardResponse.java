package com.bridgepay.application.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Ops dashboard for one period across all merchants, demo orders included. The queue is a snapshot of
 * real (non-demo) applications waiting for review.
 */
public record OpsDashboardResponse(
        int days,
        LocalDate from,
        LocalDate to,
        String bucket,
        Queue queue,
        PeriodTotals current,
        PeriodTotals previous,
        List<SeriesPoint> series,
        List<ScoreBin> scoreHistogram,
        List<Reviewer> reviewers
) {
    public record Queue(long inReview, Instant oldestSubmittedAt) {
    }

    public record PeriodTotals(long applications, long autoDecided, long reviewed, long waiting,
                               long approved, long declined, Double approvalRate, Long medianReviewSeconds) {
    }

    public record SeriesPoint(LocalDate start, long autoApproved, long autoDeclined, long review) {
    }

    public record ScoreBin(double from, double to, long count) {
    }

    public record Reviewer(String name, long decisions, long approved, long declined, Long medianReviewSeconds) {
    }
}
