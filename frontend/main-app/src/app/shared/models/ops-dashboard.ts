export interface OpsPeriodTotals {
  applications: number;
  autoDecided: number;
  reviewed: number;
  waiting: number;
  approved: number;
  declined: number;
  approvalRate: number | null;
  medianReviewSeconds: number | null;
}

export interface OpsSeriesPoint {
  start: string; // yyyy-MM-dd, local to the requested time zone
  autoApproved: number;
  autoDeclined: number;
  review: number;
}

export interface ScoreBin {
  from: number;
  to: number;
  count: number;
}

export interface ReviewerStats {
  name: string;
  decisions: number;
  approved: number;
  declined: number;
  medianReviewSeconds: number | null;
}

export interface OpsDashboard {
  days: number;
  from: string;
  to: string;
  bucket: 'DAY' | 'WEEK';
  queue: { inReview: number; oldestSubmittedAt: string | null };
  current: OpsPeriodTotals;
  previous: OpsPeriodTotals;
  series: OpsSeriesPoint[];
  scoreHistogram: ScoreBin[];
  reviewers: ReviewerStats[];
}
